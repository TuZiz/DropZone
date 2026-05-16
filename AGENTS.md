# DropZone 项目读取规范

本项目是 Kotlin + Maven 的 Bukkit/PacketEvents/ProtocolLib 插件，目标运行环境为 Minecraft 1.16.5+，Java 17 字节码。

## 当前架构

- 默认存储模式是 `LOCAL_JSON`，仅用于单服测试。
- 正式服和多服跨服使用 `MYSQL`。
- MySQL 是跨服权威数据源；本地内存只做本服在线玩家可见缓存。
- 不使用 Redis。
- 发奖通过 `dropzone_reward_outbox` 队列恢复，不能把 `Bukkit.dispatchCommand` 当作最终持久化成功。
- 假实体后端二选一：PacketEvents 或 ProtocolLib。
- 假实体表现为客户端假 ArmorStand 头颅包，不创建服务端真实实体、真实掉落物或 NMS 主实现。

## 读取顺序

1. 先读 `README.md`，确认安装方式、运行依赖和兼容边界。
2. 再读 `pom.xml` 与 `src/main/resources/plugin.yml`，确认 Java/Kotlin 版本、shade/relocate 策略、PacketEvents/ProtocolLib/PlaceholderAPI 外置依赖。
3. 配置行为从 `src/main/resources/config.yml`、`action/<活动名>/config.yml`、`heads.yml`、`rewards.yml`、`lang/zh_cn.yml` 开始读。
4. 生命周期入口读 `src/main/kotlin/ym/dropzone/DropZonePlugin.kt`。
5. 配置快照与 YAML 解析读 `config/ConfigManager.kt` 和 `config/ConfigModels.kt`。
6. MySQL 存储、迁移、事务、outbox 读 `storage/MysqlStorage.kt` 和 `storage/StorageModels.kt`。
7. 本地 JSON 单服测试模式读 `storage/ActivityStateStore.kt`。
8. 假实体逻辑读 `entity/DropZoneEntityManager.kt`，PacketEvents/ProtocolLib 适配只读 `packet/`。
9. 奖励抽取和发奖读 `reward/RewardSelector.kt`、`reward/RewardOutboxWorker.kt`、`reward/RewardCommandDispatcher.kt`、`reward/RewardModels.kt`。
10. Folia/Spigot 调度问题只在 `scheduler/` 包内处理，业务层不直接调用 BukkitScheduler。

## 修改规则

- 所有文件使用 UTF-8。
- 构建包必须是胖包：`target/DropZone-1.0.0-all.jar`。
- 胖包内置并 relocate Kotlin、Adventure、HikariCP、MySQL JDBC、Gson 等核心依赖。
- 不要把 Spigot/Paper API、PacketEvents、ProtocolLib 或 PlaceholderAPI 打进 jar。
- 不要使用 `plugin.yml libraries`。
- 不要在主线程或 Folia region thread 做 MySQL 或 YAML 文件 IO。
- YAML 只允许在启动、`/dz reload`、`/dz start` 时读取；跨服同步不能周期性 reload YAML。
- 不要在领取奖励、tick、视距更新中读取配置文件。
- 玩家可见文本必须放在 `lang/zh_cn.yml`，使用 MiniMessage RGB/Hex。
- 活动内容必须放入 `action/<活动名>/`，不要在资源根目录新增 `heads.yml` 或 `rewards.yml`。
- `action/<活动名>/` 只放活动资源配置，不放运行数据存储配置。
- 配置、奖励、头颅、调度、发包、命令各自分层，主类只做生命周期组装。
- 新增模型类优先放入本包已有 `*Models.kt`，只有独立复杂职责才单独拆文件。

## 构建验证

每次修改后至少运行：

```bash
mvn clean package
```

构建产物为：

```text
target/DropZone-1.0.0-all.jar
```

jar 内应包含 relocated `ym/dropzone/libs/kotlin/`、`ym/dropzone/libs/adventure/`、`ym/dropzone/libs/mysql/`、`ym/dropzone/libs/hikari/`、`ym/dropzone/libs/gson/`，但不应包含 `com/github/retrooper/packetevents/`、`com/comphenix/protocol/` 或 `me/clip/placeholderapi/` 依赖包主体。
