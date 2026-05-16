# DropZone 项目读取规范

本项目是 Kotlin + Maven 的 Bukkit/PacketEvents 插件，目标运行环境为 Minecraft 1.16+，Java 17 字节码。构建产物内置 Kotlin、Adventure 和常用数据库驱动，PacketEvents 与 PlaceholderAPI 仍作为服务器外置插件依赖。

## 读取顺序

1. 先读 `README.md`，确认安装方式、运行依赖和兼容边界。
2. 再读 `pom.xml` 与 `src/main/resources/plugin.yml`，确认 Java/Kotlin 版本、shade 策略、PacketEvents 外置依赖和 PlaceholderAPI 软依赖。
3. 配置行为从 `src/main/resources/config.yml`、`action/<活动名>/config.yml`、`heads.yml`、`rewards.yml`、`lang/zh_cn.yml` 开始读。
4. 生命周期入口读 `src/main/kotlin/ym/dropzone/DropZonePlugin.kt`。
5. 配置快照与 YAML 解析读 `config/ConfigManager.kt` 和 `config/ConfigModels.kt`。
6. 活动状态和跨服存储扩展点读 `storage/ActivityStateStore.kt`，默认实现是本地 JSON。
7. 假实体逻辑读 `entity/DropZoneEntityManager.kt`，PacketEvents 版本适配只读 `packet/PacketEventsEntityAdapter.kt`。
8. 奖励抽取和发奖读 `reward/RewardSelector.kt`、`reward/RewardExecutor.kt`、`reward/RewardModels.kt`。
9. Folia/Spigot 调度问题只在 `scheduler/` 包内处理，业务层不直接调用 BukkitScheduler。

## 修改规则

- 所有文件使用 UTF-8。
- 构建包需要内置 Kotlin、Adventure、MySQL、SQLite、PostgreSQL 驱动；不要把 Spigot/Paper API、PacketEvents 或 PlaceholderAPI 打进 jar。
- 不要新增 ProtocolLib、NMS、真实掉落物、ArmorStand 或 Display Entity 主实现。
- 不要在主线程或 region thread 做 YAML 文件 IO。
- 不要在领取奖励、tick、视距更新中读取配置文件。
- 玩家可见文本必须放在 `lang/zh_cn.yml`，使用 MiniMessage RGB/Hex。
- 活动内容必须放入 `action/<活动名>/`，不要在资源根目录新增 `heads.yml` 或 `rewards.yml`。
- `action/<活动名>/` 只放活动资源配置，不放运行数据存储配置。
- 当前活动状态默认由根 `config.yml` 的 `state-storage` 节点控制，默认写入 `data/activity-state.json`。
- 配置、奖励、头颅、调度、发包、命令各自分层，主类只做生命周期组装。
- 新增模型类优先放入本包已有 `*Models.kt`，避免为 5-20 行纯数据类单独创建文件。
- 一个包内不要堆大量低内容文件；只有当类有独立复杂职责时才单独拆文件。

## 构建验证

每次修改后至少运行：

```bash
mvn clean package
```

构建产物为 `target/DropZone-1.0.0.jar`，jar 内应包含 `kotlin/`、`net/kyori/`、`com/mysql/`、`org/sqlite/`、`org/postgresql/`，但不应包含 `com/github/retrooper/packetevents/` 或 `me/clip/placeholderapi/` 依赖包主体。
