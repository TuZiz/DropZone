# Changelog

本项目遵循面向服主的变更记录格式。日期使用北京时间。

## [1.0.0-rc.1] - 2026-05-16

### 新增

- 新增启动时自动诊断日志，只输出到控制台，不发送给玩家。
- 启动诊断包含插件版本、当前启用活动、存储模式、服务器 ID / 分组、跨服开关、生成模式、MySQL 状态、本服活跃奖励点数量、发奖队列统计、ProtocolLib 状态、盔甲架参数、发奖队列配置、调试开关、PlaceholderAPI 状态和 Folia 状态。
- 新增危险配置启动警告，覆盖发包调试、可见盔甲架、MySQL 模式关闭发奖队列、`ANY_SERVER` 消费模式、过高活跃奖励点上限、过短生成间隔、同步区块加载、`armor-stand-marker=true`、ProtocolLib 缺失和旧 PacketEvents 配置。
- 新增 `StartupDiagnosticService`，避免把启动诊断逻辑堆入主类。
- README 增加单服部署、多服部署、启动诊断、头颅高度调整、旧版本升级和正式服上线检查清单。
- 新增本文件和 `RELEASE_NOTES.md`，用于发布前整理和测试服交付。

### 变更

- 插件版本统一为 `1.0.0-rc.1`。
- Maven 胖包产物更新为 `target/DropZone-1.0.0-rc.1-all.jar`。
- 启动和诊断相关控制台提示改为中文。
- `fake-entity.packet-backend=packetevents` 不再导致枚举解析崩溃，会自动回退到 `PROTOCOLLIB` 并提示服主修改配置。
- ProtocolLib 未安装或未启用时，启动阶段会给出清晰错误并禁用插件。
- `MYSQL` 模式下 `server.id` 和 `server.group` 为空时拒绝启动。
- 启动诊断常规输出不再显示假实体后端，只有旧配置或异常场景才提示后端相关信息。

### 保持不变

- 未新增 `/dz doctor` 命令。
- 未新增 `dropzone.doctor` 权限。
- 未新增 GUI、排行榜、Redis 或 PacketEvents。
- 未重构 MySQL 事务、Outbox 事务、实体吸附逻辑或头颅显示核心逻辑。
- 继续要求 ProtocolLib 作为唯一假实体发包后端。

### 验证

- `mvn clean package` 通过。
- 胖包包含 relocated Kotlin、Adventure、MySQL JDBC、HikariCP、Gson。
- 胖包不包含 ProtocolLib 或 PlaceholderAPI 主体依赖。

## [1.0.0] - 之前版本

### 已有能力

- ProtocolLib 假头颅显示。
- 奖励点随机生成。
- 玩家靠近吸附。
- 领取奖励。
- `LOCAL_JSON` 单服测试模式。
- `MYSQL` 正式服和多服跨服模式。
- 多服数据库同步。
- 可恢复的 Outbox 发奖队列。
- Maven / GitHub Actions 构建。
