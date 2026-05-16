# DropZone

DropZone 是一个 Bukkit/Paper/Purpur/Folia 插件，会在配置区域内随机生成客户端可见的假头颅奖励点。玩家靠近后奖励点会飞向玩家，领取成功后通过控制台命令发奖。

假实体后端使用 ProtocolLib 发送客户端假 ArmorStand 头颅包，不创建服务端真实实体。

## 胖包说明

本项目使用 Maven 构建胖包：

```bash
mvn clean package
```

正式产物：

```text
target/DropZone-1.0.0-all.jar
```

插件不使用 `plugin.yml libraries`。Kotlin、Adventure/MiniMessage、HikariCP、MySQL JDBC、Gson 会被 shade 并 relocate 到 `ym.dropzone.libs.*`。

ProtocolLib、PlaceholderAPI 仍由服务器插件环境提供，不会被打进 DropZone jar。

## 运行依赖

- Java 17
- Minecraft 1.16.5+
- Spigot / Paper / Purpur / Folia
- ProtocolLib
- PlaceholderAPI 可选
- MySQL 8.x 或兼容 MySQL 协议的数据库

## 存储模式

默认配置使用 `LOCAL_JSON`，方便单服直接启动测试。`storage.mode` 支持：

- `LOCAL_JSON`：仅适合单服测试。
- `MYSQL`：正式服和多服跨服模式，需要手动启用。

`cross-server.enabled=true` 时必须使用 `MYSQL`，并把 `spawn.cross-server-mode` 改为 `DATABASE_LOCK`，否则插件会拒绝启动，不会静默回退到本地 JSON。

## MySQL 配置

启用 MySQL 时，将 `storage.mode` 改为 `MYSQL`：

```yaml
storage:
  mode: MYSQL
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "dropzone"
    username: "dropzone"
    password: "password"
    params: "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
    pool-size: 5
    connection-timeout-ms: 10000
    max-lifetime-ms: 1800000
```

所有数据库 IO 都在线程池中执行，不在 Bukkit 主线程或 Folia region thread 阻塞。

## 多服部署示例

Velocity 网络示例：

- `survival-1`
- `survival-2`
- `resource`
- `lobby`

同一组服务器使用相同 `server.group`，共享奖励点状态：

```yaml
server:
  id: "survival-1"
  group: "survival"

cross-server:
  enabled: true
  sync-interval-seconds: 3

spawn:
  cross-server-mode: "DATABASE_LOCK"
```

每台子服的 `server.id` 必须唯一。数据库是唯一权威数据源，本地内存只是缓存；定时同步会修复错过的领取、过期和清理状态。

## 数据库表

启动时自动建表：

- `dropzone_schema_version`：结构版本。
- `dropzone_activity_state`：当前活动与版本。
- `dropzone_spawn_points`：奖励点权威状态，包含 WAITING/CLAIMED/EXPIRED/CLEARED。
- `dropzone_claims`：玩家领取历史，用于次数、冷却和重复奖励限制。
- `dropzone_reward_outbox`：可恢复发奖队列。
- `dropzone_locks`：跨服生成锁，避免多服同时生成过量奖励点。

领取事务会 `SELECT ... FOR UPDATE` 锁定奖励点，检查状态、次数、冷却、重复奖励限制，然后更新奖励点、写领取记录、写 outbox，并在事务提交后才移除本地假实体。

## Outbox 发奖队列

领取成功后不会直接把 `Bukkit.dispatchCommand` 当作最终成功。插件先写入 `dropzone_reward_outbox`，再由 `RewardOutboxWorker` 扫描 PENDING 任务，通过 `SchedulerAdapter` 切到安全调度器执行命令。

任务成功后标记 `DONE`，失败会回到 `PENDING` 重试，超过最大次数标记 `FAILED`。服务器崩溃重启后仍会继续处理未完成任务。

关键配置位于 `reward-outbox.yml`：

```yaml
reward-outbox:
  enabled: true
  poll-interval-seconds: 2
  max-attempts: 5
  claim-batch-size: 20
  processing-timeout-seconds: 60
  consume-mode: "CURRENT_SERVER"
```

`consume-mode` 支持：

- `CURRENT_SERVER`：默认，只消费当前 `server.id` 的发奖任务。
- `SAME_GROUP`：只消费当前 `server.group` 的发奖任务。
- `ANY_SERVER`：任意服务器都可消费，通常不建议在混合网络中使用。

## 命令

```text
/dz reload
/dz start <activity>
/dz spawn [amount]
/dz clear
/dz list
/dz debug
/dz sync
/dz dbstatus
/dz outbox
/dz outbox retry <id>
/dz outbox failed
```

新增权限：

```text
dropzone.sync
dropzone.dbstatus
dropzone.outbox
```

## Folia 注意事项

插件保留 `SchedulerAdapter`。玩家操作通过 player scheduler，世界/位置操作通过 region scheduler，全局命令通过 global scheduler。`reward-command.executor` 默认是 `GLOBAL_SAFE`。

部分第三方插件命令可能不完全兼容 Folia，请确认奖励命令目标插件支持当前调度方式。

## 头颅显示高度调整

ProtocolLib 假头颅通过客户端假 ArmorStand 头盔显示，可以在 `fake-entity.yml` 调整载体参数：

```yaml
fake-entity:
  armor-stand-y-offset: -0.85
  armor-stand-small: true
  armor-stand-marker: false
```

- 头颅卡进方块：把 `armor-stand-y-offset` 从 `-0.85` 调到 `-0.65`。
- 头颅太高：把 `armor-stand-y-offset` 从 `-0.85` 调到 `-1.05`。
- 仍看不到：临时打开 `debug-visible-armorstand: true` 和 `debug-packets: true` 排查。
- 排查完成后记得关闭 `debug-packets`，避免控制台刷发包日志。

## 常见问题

**是否需要 Redis？**  
不需要。当前版本使用 MySQL 权威状态、本地缓存和定时同步，不引入 Redis。

**是否需要 plugin.yml libraries？**  
不需要。本插件是胖包。

**ProtocolLib 是否会被打进 jar？**  
不会。ProtocolLib 仍需单独安装到服务器 `plugins/` 目录。

**LOCAL_JSON 能用于多服吗？**  
不能。LOCAL_JSON 只适合单服测试。
