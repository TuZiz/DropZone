package ym.dropzone.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.dropzone.config.MysqlConfig
import ym.dropzone.config.OutboxConsumeMode
import ym.dropzone.config.ServerConfig
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class MysqlStorage(
    mysql: MysqlConfig,
    private val server: ServerConfig,
    private val defaultActivity: String
) : ActivityStateStore, AutoCloseable {
    private val gson = Gson()
    private val executor: ExecutorService = Executors.newFixedThreadPool(mysql.poolSize.coerceAtLeast(2), NamedThreadFactory("DropZone-MySQL"))
    private val dataSource: HikariDataSource

    init {
        val params = mysql.params.trim().trimStart('?')
        val suffix = if (params.isBlank()) "" else "?$params"
        val jdbcUrl = "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}$suffix"
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = mysql.username
            password = mysql.password
            maximumPoolSize = mysql.poolSize
            connectionTimeout = mysql.connectionTimeoutMs
            maxLifetime = mysql.maxLifetimeMs
            poolName = "DropZone-Hikari"
        }
        dataSource = HikariDataSource(config)
    }

    fun initialize(): CompletableFuture<Unit> = async {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            migrate(connection)
            connection.commit()
        }
    }

    fun isHealthy(): CompletableFuture<Boolean> = async {
        dataSource.connection.use { connection -> connection.isValid(3) }
    }

    override fun read(): ActivityState {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT active_activity, updated_at FROM dropzone_activity_state WHERE id = ?").use { statement ->
                statement.setString(1, server.group)
                statement.executeQuery().use { rs ->
                    if (rs.next()) return ActivityState(rs.getString("active_activity"), Instant.ofEpochMilli(rs.getLong("updated_at")).toString())
                }
            }
            write(defaultActivity)
            return read()
        }
    }

    override fun write(activityName: String): ActivityState {
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO dropzone_activity_state(id, active_activity, version, updated_at, updated_by_server)
                VALUES (?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    active_activity = VALUES(active_activity),
                    version = version + 1,
                    updated_at = VALUES(updated_at),
                    updated_by_server = VALUES(updated_by_server)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, server.group)
                statement.setString(2, activityName)
                statement.setLong(3, now)
                statement.setString(4, server.id)
                statement.executeUpdate()
            }
        }
        return ActivityState(activityName, Instant.ofEpochMilli(now).toString())
    }

    fun createSpawnPoint(point: StoredSpawnPoint): CompletableFuture<Boolean> = async {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO dropzone_spawn_points(
                    id, activity_id, server_group, world_name, world_uuid, x, y, z,
                    rarity_id, head_id, reward_id, state, created_at, expires_at,
                    created_by_server, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING', ?, ?, ?, 1)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, point.id.toString())
                statement.setString(2, point.activityId)
                statement.setString(3, point.serverGroup)
                statement.setString(4, point.worldName)
                statement.setString(5, point.worldUuid)
                statement.setDouble(6, point.x)
                statement.setDouble(7, point.y)
                statement.setDouble(8, point.z)
                statement.setString(9, point.rarityId)
                statement.setString(10, point.headId)
                statement.setString(11, point.rewardId)
                statement.setLong(12, point.createdAt)
                statement.setLong(13, point.expiresAt)
                statement.setString(14, point.createdByServer)
                statement.executeUpdate() == 1
            }
        }
    }

    fun acquireLock(lockName: String, ttlMillis: Long): CompletableFuture<Boolean> = async {
        val now = System.currentTimeMillis()
        val until = now + ttlMillis.coerceAtLeast(1000L)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO dropzone_locks(lock_name, owner_server, locked_until, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner_server = IF(locked_until < ?, VALUES(owner_server), owner_server),
                    locked_until = IF(locked_until < ?, VALUES(locked_until), locked_until),
                    updated_at = IF(locked_until < ?, VALUES(updated_at), updated_at)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, lockName)
                statement.setString(2, server.id)
                statement.setLong(3, until)
                statement.setLong(4, now)
                statement.setLong(5, now)
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT owner_server, locked_until FROM dropzone_locks WHERE lock_name = ?").use { statement ->
                statement.setString(1, lockName)
                statement.executeQuery().use { rs ->
                    rs.next() && rs.getString("owner_server") == server.id && rs.getLong("locked_until") >= now
                }
            }
        }
    }

    fun activeCount(activityId: String, group: String): CompletableFuture<Int> = async {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM dropzone_spawn_points WHERE activity_id = ? AND server_group = ? AND state = 'WAITING' AND expires_at > ?"
            ).use { statement ->
                statement.setString(1, activityId)
                statement.setString(2, group)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    fun claim(request: MysqlClaimRequest): CompletableFuture<MysqlClaimResult> = async {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val spawn = lockSpawn(connection, request.spawnId, request.serverGroup)
                    ?: return@async rollback(connection, MysqlClaimResult(false, MysqlClaimDenyReason.NOT_FOUND))
                if (spawn.state != SpawnPointState.WAITING || spawn.expiresAt <= request.now) {
                    return@async rollback(connection, MysqlClaimResult(false, MysqlClaimDenyReason.ALREADY_CLAIMED))
                }
                val totalClaims = countPlayerClaims(connection, request.playerUuid, request.activityId)
                if (request.rules.maxClaimsPerPlayer > 0 && totalClaims >= request.rules.maxClaimsPerPlayer) {
                    return@async rollback(connection, MysqlClaimResult(false, MysqlClaimDenyReason.MAX_CLAIMS))
                }
                if (request.rules.claimCooldownSeconds > 0) {
                    val lastClaimAt = lastClaimAt(connection, request.playerUuid, request.activityId)
                    val cooldownMillis = request.rules.claimCooldownSeconds * 1000L
                    if (lastClaimAt > 0 && request.now - lastClaimAt < cooldownMillis) {
                        val remaining = ceil((cooldownMillis - (request.now - lastClaimAt)) / 1000.0).toLong().coerceAtLeast(1L)
                        return@async rollback(connection, MysqlClaimResult(false, MysqlClaimDenyReason.COOLDOWN, remainingSeconds = remaining))
                    }
                }
                if (!request.rules.allowRepeatRewards && hasRewardClaim(connection, request.playerUuid, request.activityId, request.rewardId)) {
                    return@async rollback(connection, MysqlClaimResult(false, MysqlClaimDenyReason.REPEAT_REWARD))
                }
                markClaimed(connection, request)
                insertClaim(connection, request)
                val outboxId = insertOutbox(connection, request)
                connection.commit()
                MysqlClaimResult(true, outboxId = outboxId)
            } catch (error: Throwable) {
                connection.rollback()
                MysqlClaimResult(false, MysqlClaimDenyReason.FAILED, error = error.message ?: error.javaClass.simpleName)
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun markExpired(spawnId: UUID): CompletableFuture<Unit> = async {
        updateState(spawnId, SpawnPointState.EXPIRED)
    }

    fun clearActive(activityId: String, group: String): CompletableFuture<Int> = async {
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE dropzone_spawn_points
                SET state = 'CLEARED', version = version + 1, claimed_on_server = ?, claimed_at = ?
                WHERE activity_id = ? AND server_group = ? AND state IN ('WAITING', 'CLAIMING')
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, server.id)
                statement.setLong(2, now)
                statement.setString(3, activityId)
                statement.setString(4, group)
                statement.executeUpdate()
            }
        }
    }

    fun loadWaitingSpawns(activityId: String, group: String): CompletableFuture<List<StoredSpawnPoint>> = async {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT * FROM dropzone_spawn_points
                WHERE activity_id = ? AND server_group = ? AND state = 'WAITING' AND expires_at > ?
                ORDER BY created_at ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, activityId)
                statement.setString(2, group)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeQuery().use { rs ->
                    val points = mutableListOf<StoredSpawnPoint>()
                    while (rs.next()) points += readSpawn(rs)
                    points
                }
            }
        }
    }

    fun restoreStaleProcessing(processingTimeoutSeconds: Long): CompletableFuture<Int> = async {
        val now = System.currentTimeMillis()
        val cutoff = now - processingTimeoutSeconds.coerceAtLeast(5L) * 1000L
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE dropzone_reward_outbox
                SET status = IF(attempt_count >= max_attempts, 'FAILED', 'PENDING'),
                    last_error = IF(attempt_count >= max_attempts, 'processing timeout exceeded max attempts', last_error),
                    updated_at = ?
                WHERE status = 'PROCESSING' AND updated_at < ?
                """.trimIndent()
            ).use {
                it.setLong(1, now)
                it.setLong(2, cutoff)
                it.executeUpdate()
            }
        }
    }

    fun claimOutboxBatch(limit: Int, consumeMode: OutboxConsumeMode): CompletableFuture<List<RewardOutboxEntry>> = async {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val ids = mutableListOf<Long>()
                val consumeClause = when (consumeMode) {
                    OutboxConsumeMode.CURRENT_SERVER -> " AND server_id = ?"
                    OutboxConsumeMode.SAME_GROUP -> " AND server_group = ?"
                    OutboxConsumeMode.ANY_SERVER -> ""
                }
                connection.prepareStatement(
                    """
                    SELECT id FROM dropzone_reward_outbox
                    WHERE status = 'PENDING'$consumeClause
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE
                    """.trimIndent()
                ).use { statement ->
                    var index = 1
                    when (consumeMode) {
                        OutboxConsumeMode.CURRENT_SERVER -> statement.setString(index++, server.id)
                        OutboxConsumeMode.SAME_GROUP -> statement.setString(index++, server.group)
                        OutboxConsumeMode.ANY_SERVER -> Unit
                    }
                    statement.setInt(index, limit)
                    statement.executeQuery().use { rs -> while (rs.next()) ids += rs.getLong("id") }
                }
                val entries = ids.mapNotNull { id ->
                    val updated = connection.prepareStatement(
                        """
                        UPDATE dropzone_reward_outbox
                        SET status = 'PROCESSING', attempt_count = attempt_count + 1, updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, System.currentTimeMillis())
                        statement.setLong(2, id)
                        statement.executeUpdate()
                    }
                    if (updated == 1) readOutbox(connection, id) else null
                }
                connection.commit()
                entries
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun markOutboxDone(id: Long): CompletableFuture<Unit> = async {
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE dropzone_reward_outbox SET status = 'DONE', done_at = ?, updated_at = ? WHERE id = ?").use {
                it.setLong(1, now)
                it.setLong(2, now)
                it.setLong(3, id)
                it.executeUpdate()
            }
        }
    }

    fun markOutboxFailedOrPending(id: Long, error: String): CompletableFuture<Unit> = async {
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE dropzone_reward_outbox
                SET status = IF(attempt_count < max_attempts, 'PENDING', 'FAILED'),
                    last_error = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use {
                it.setString(1, error.take(4000))
                it.setLong(2, now)
                it.setLong(3, id)
                it.executeUpdate()
            }
        }
    }

    fun retryOutbox(id: Long): CompletableFuture<Boolean> = async {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE dropzone_reward_outbox SET status = 'PENDING', updated_at = ? WHERE id = ? AND status = 'FAILED'"
            ).use {
                it.setLong(1, System.currentTimeMillis())
                it.setLong(2, id)
                it.executeUpdate() == 1
            }
        }
    }

    fun outboxStats(): CompletableFuture<OutboxStats> = async {
        val counts = mutableMapOf<String, Long>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT status, COUNT(*) count_value FROM dropzone_reward_outbox GROUP BY status").use { rs ->
                    while (rs.next()) counts[rs.getString("status")] = rs.getLong("count_value")
                }
            }
        }
        OutboxStats(
            pending = counts["PENDING"] ?: 0L,
            processing = counts["PROCESSING"] ?: 0L,
            done = counts["DONE"] ?: 0L,
            failed = counts["FAILED"] ?: 0L
        )
    }

    private fun migrate(connection: Connection) {
        connection.createStatement().use { statement ->
            statements().forEach(statement::executeUpdate)
        }
        ensureColumn(connection, "dropzone_reward_outbox", "server_group", "ALTER TABLE dropzone_reward_outbox ADD COLUMN server_group VARCHAR(128) NULL AFTER activity_id")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                UPDATE dropzone_reward_outbox o
                JOIN dropzone_spawn_points s ON s.id = o.spawn_id
                SET o.server_group = s.server_group
                WHERE o.server_group IS NULL
                """.trimIndent()
            )
            statement.executeUpdate("UPDATE dropzone_reward_outbox SET server_group = '${escapeSql(server.group)}' WHERE server_group IS NULL")
        }
        connection.prepareStatement(
            """
            INSERT INTO dropzone_schema_version(id, version, updated_at)
            VALUES (1, 2, ?)
            ON DUPLICATE KEY UPDATE version = GREATEST(version, VALUES(version)), updated_at = VALUES(updated_at)
            """.trimIndent()
        ).use {
            it.setLong(1, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    private fun ensureColumn(connection: Connection, table: String, column: String, alterSql: String) {
        connection.metaData.getColumns(connection.catalog, null, table, column).use { columns ->
            if (!columns.next()) {
                connection.createStatement().use { it.executeUpdate(alterSql) }
            }
        }
    }

    private fun escapeSql(value: String): String = value.replace("'", "''")

    private fun statements(): List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS dropzone_schema_version(id INT PRIMARY KEY, version INT NOT NULL, updated_at BIGINT NOT NULL)",
        """
        CREATE TABLE IF NOT EXISTS dropzone_activity_state(
            id VARCHAR(64) PRIMARY KEY,
            active_activity VARCHAR(128) NOT NULL,
            version BIGINT NOT NULL,
            updated_at BIGINT NOT NULL,
            updated_by_server VARCHAR(128)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS dropzone_spawn_points(
            id VARCHAR(64) PRIMARY KEY,
            activity_id VARCHAR(128) NOT NULL,
            server_group VARCHAR(128) NOT NULL,
            world_name VARCHAR(128) NOT NULL,
            world_uuid VARCHAR(64),
            x DOUBLE NOT NULL,
            y DOUBLE NOT NULL,
            z DOUBLE NOT NULL,
            rarity_id VARCHAR(64) NOT NULL,
            head_id VARCHAR(128) NOT NULL,
            reward_id VARCHAR(128) NOT NULL,
            state VARCHAR(32) NOT NULL,
            created_at BIGINT NOT NULL,
            expires_at BIGINT NOT NULL,
            claimed_by VARCHAR(64),
            claimed_at BIGINT,
            claimed_on_server VARCHAR(128),
            created_by_server VARCHAR(128),
            version BIGINT NOT NULL,
            INDEX idx_activity_group_state(activity_id, server_group, state),
            INDEX idx_expires_at(expires_at)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS dropzone_claims(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            spawn_id VARCHAR(64) NOT NULL,
            activity_id VARCHAR(128) NOT NULL,
            player_uuid VARCHAR(64) NOT NULL,
            player_name VARCHAR(32) NOT NULL,
            reward_id VARCHAR(128) NOT NULL,
            rarity_id VARCHAR(64) NOT NULL,
            server_id VARCHAR(128) NOT NULL,
            claimed_at BIGINT NOT NULL,
            UNIQUE KEY uk_spawn_player(spawn_id, player_uuid),
            INDEX idx_player_activity(player_uuid, activity_id),
            INDEX idx_player_reward(player_uuid, reward_id),
            INDEX idx_claimed_at(claimed_at)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS dropzone_reward_outbox(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            spawn_id VARCHAR(64) NOT NULL,
            activity_id VARCHAR(128) NOT NULL,
            server_group VARCHAR(128) NOT NULL,
            player_uuid VARCHAR(64) NOT NULL,
            player_name VARCHAR(32) NOT NULL,
            reward_id VARCHAR(128) NOT NULL,
            rarity_id VARCHAR(64) NOT NULL,
            server_id VARCHAR(128) NOT NULL,
            commands_json TEXT NOT NULL,
            status VARCHAR(32) NOT NULL,
            attempt_count INT NOT NULL DEFAULT 0,
            max_attempts INT NOT NULL DEFAULT 5,
            last_error TEXT,
            created_at BIGINT NOT NULL,
            updated_at BIGINT NOT NULL,
            done_at BIGINT,
            INDEX idx_status_created(status, created_at),
            INDEX idx_group_status(server_group, status, created_at),
            INDEX idx_server_status(server_id, status, created_at),
            INDEX idx_player_uuid(player_uuid),
            INDEX idx_spawn_id(spawn_id)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS dropzone_locks(
            lock_name VARCHAR(128) PRIMARY KEY,
            owner_server VARCHAR(128) NOT NULL,
            locked_until BIGINT NOT NULL,
            updated_at BIGINT NOT NULL
        )
        """.trimIndent()
    )

    private fun lockSpawn(connection: Connection, spawnId: UUID, group: String): StoredSpawnPoint? {
        connection.prepareStatement("SELECT * FROM dropzone_spawn_points WHERE id = ? AND server_group = ? FOR UPDATE").use { statement ->
            statement.setString(1, spawnId.toString())
            statement.setString(2, group)
            statement.executeQuery().use { rs -> return if (rs.next()) readSpawn(rs) else null }
        }
    }

    private fun countPlayerClaims(connection: Connection, playerUuid: UUID, activityId: String): Int {
        connection.prepareStatement("SELECT COUNT(*) FROM dropzone_claims WHERE player_uuid = ? AND activity_id = ?").use {
            it.setString(1, playerUuid.toString())
            it.setString(2, activityId)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun lastClaimAt(connection: Connection, playerUuid: UUID, activityId: String): Long {
        connection.prepareStatement("SELECT MAX(claimed_at) FROM dropzone_claims WHERE player_uuid = ? AND activity_id = ?").use {
            it.setString(1, playerUuid.toString())
            it.setString(2, activityId)
            it.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun hasRewardClaim(connection: Connection, playerUuid: UUID, activityId: String, rewardId: String): Boolean {
        connection.prepareStatement("SELECT 1 FROM dropzone_claims WHERE player_uuid = ? AND activity_id = ? AND reward_id = ? LIMIT 1").use {
            it.setString(1, playerUuid.toString())
            it.setString(2, activityId)
            it.setString(3, rewardId)
            it.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun markClaimed(connection: Connection, request: MysqlClaimRequest) {
        connection.prepareStatement(
            """
            UPDATE dropzone_spawn_points
            SET state = 'CLAIMED', claimed_by = ?, claimed_at = ?, claimed_on_server = ?, version = version + 1
            WHERE id = ? AND state = 'WAITING'
            """.trimIndent()
        ).use {
            it.setString(1, request.playerUuid.toString())
            it.setLong(2, request.now)
            it.setString(3, request.serverId)
            it.setString(4, request.spawnId.toString())
            if (it.executeUpdate() != 1) throw IllegalStateException("spawn already claimed")
        }
    }

    private fun insertClaim(connection: Connection, request: MysqlClaimRequest) {
        connection.prepareStatement(
            """
            INSERT INTO dropzone_claims(spawn_id, activity_id, player_uuid, player_name, reward_id, rarity_id, server_id, claimed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use {
            it.setString(1, request.spawnId.toString())
            it.setString(2, request.activityId)
            it.setString(3, request.playerUuid.toString())
            it.setString(4, request.playerName.take(32))
            it.setString(5, request.rewardId)
            it.setString(6, request.rarityId)
            it.setString(7, request.serverId)
            it.setLong(8, request.now)
            it.executeUpdate()
        }
    }

    private fun insertOutbox(connection: Connection, request: MysqlClaimRequest): Long {
        connection.prepareStatement(
            """
            INSERT INTO dropzone_reward_outbox(
                spawn_id, activity_id, server_group, player_uuid, player_name, reward_id, rarity_id, server_id,
                commands_json, status, attempt_count, max_attempts, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use {
            it.setString(1, request.spawnId.toString())
            it.setString(2, request.activityId)
            it.setString(3, request.serverGroup)
            it.setString(4, request.playerUuid.toString())
            it.setString(5, request.playerName.take(32))
            it.setString(6, request.rewardId)
            it.setString(7, request.rarityId)
            it.setString(8, request.serverId)
            it.setString(9, gson.toJson(request.commands))
            it.setInt(10, request.maxAttempts)
            it.setLong(11, request.now)
            it.setLong(12, request.now)
            it.executeUpdate()
            it.generatedKeys.use { keys -> return if (keys.next()) keys.getLong(1) else 0L }
        }
    }

    private fun updateState(spawnId: UUID, state: SpawnPointState) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE dropzone_spawn_points SET state = ?, version = version + 1 WHERE id = ? AND state = 'WAITING'"
            ).use {
                it.setString(1, state.name)
                it.setString(2, spawnId.toString())
                it.executeUpdate()
            }
        }
    }

    private fun readSpawn(rs: ResultSet): StoredSpawnPoint = StoredSpawnPoint(
        id = UUID.fromString(rs.getString("id")),
        activityId = rs.getString("activity_id"),
        serverGroup = rs.getString("server_group"),
        worldName = rs.getString("world_name"),
        worldUuid = rs.getString("world_uuid"),
        x = rs.getDouble("x"),
        y = rs.getDouble("y"),
        z = rs.getDouble("z"),
        rarityId = rs.getString("rarity_id"),
        headId = rs.getString("head_id"),
        rewardId = rs.getString("reward_id"),
        state = SpawnPointState.valueOf(rs.getString("state")),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        claimedBy = rs.getString("claimed_by"),
        claimedAt = rs.getLong("claimed_at").takeIf { !rs.wasNull() },
        claimedOnServer = rs.getString("claimed_on_server"),
        createdByServer = rs.getString("created_by_server"),
        version = rs.getLong("version")
    )

    private fun readOutbox(connection: Connection, id: Long): RewardOutboxEntry? {
        connection.prepareStatement("SELECT * FROM dropzone_reward_outbox WHERE id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs ->
                if (!rs.next()) return null
                val listType = object : TypeToken<List<String>>() {}.type
                return RewardOutboxEntry(
                    id = rs.getLong("id"),
                    spawnId = rs.getString("spawn_id"),
                    activityId = rs.getString("activity_id"),
                    serverGroup = rs.getString("server_group"),
                    playerUuid = UUID.fromString(rs.getString("player_uuid")),
                    playerName = rs.getString("player_name"),
                    rewardId = rs.getString("reward_id"),
                    rarityId = rs.getString("rarity_id"),
                    serverId = rs.getString("server_id"),
                    commands = gson.fromJson(rs.getString("commands_json"), listType),
                    attemptCount = rs.getInt("attempt_count"),
                    maxAttempts = rs.getInt("max_attempts")
                )
            }
        }
    }

    private fun <T> async(task: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(java.util.function.Supplier { task() }, executor)

    private fun <T> rollback(connection: Connection, result: T): T {
        connection.rollback()
        return result
    }

    override fun close() {
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        dataSource.close()
    }

    private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
        private var index = 0
        override fun newThread(runnable: Runnable): Thread {
            index += 1
            return Thread(runnable, "$prefix-$index").apply { isDaemon = true }
        }
    }
}
