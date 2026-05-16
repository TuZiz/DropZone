# DropZone

DropZone 是一个 Bukkit/Paper/Purpur/Folia 插件，会在配置区域内随机生成客户端可见的假头颅奖励点。玩家靠近后奖励点会飞向玩家，领取成功后通过控制台命令发奖。

当前版本：`1.0.0-rc.1`

假实体后端只使用 ProtocolLib 发送客户端假 ArmorStand 头颅包，不创建服务端真实实体、真实掉落物或 NMS 主实现。

## 胖包说明

本项目使用 Maven 构建胖包：

```bash
mvn clean package
```

正式产物：

```text
target/DropZone-1.0.0-rc.1-all.jar
```

插件不使用 `plugin.yml libraries`。Kotlin、Adventure/MiniMessage、HikariCP、MySQL JDBC、Gson 会被 shade 并 relocate 到 `ym.dropzone.libs.*`。

ProtocolLib、PlaceholderAPI 由服务器插件环境提供，不会被打进 DropZone jar。

## 运行依赖

- Java 17
- Minecraft 1.16.5+
- Spigot / Paper / Purpur / Folia
- ProtocolLib 必须安装
- PlaceholderAPI 可选
- MySQL 8.x 或兼容 MySQL 协议的数据库

PacketEvents 已不再需要。旧配置如果还有 `packet-backend: packetevents`，请改成 `protocolib`。

## 存储模式

默认配置使用 `LOCAL_JSON`，仅适合单服测试。

- `LOCAL_JSON`：单服测试。
- `MYSQL`：正式服和多服跨服模式。

`cross-server.enabled=true` 时必须使用 `MYSQL`，并把 `spawn.cross-server-mode` 改为 `DATABASE_LOCK`，否则插件会拒绝启动。

## MySQL 配置

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

## 单服部署推荐配置

```yaml
storage:
  mode: MYSQL

cross-server:
  enabled: false

spawn:
  cross-server-mode: LOCAL_ONLY

reward-outbox:
  enabled: true
  consume-mode: CURRENT_SERVER

fake-entity:
  packet-backend: protocolib
  armor-stand-y-offset: -0.85
  armor-stand-small: true
  armor-stand-marker: false
```

## 多服部署推荐配置

```yaml
storage:
  mode: MYSQL

server:
  id: "survival-1"
  group: "survival"

cross-server:
  enabled: true

spawn:
  cross-server-mode: DATABASE_LOCK

reward-outbox:
  enabled: true
  consume-mode: CURRENT_SERVER

fake-entity:
  packet-backend: protocolib
```

每台子服 `server.id` 必须唯一。同一组玩法服务器使用相同 `server.group`。lobby 不建议和 survival 共用 group。ProtocolLib 必须安装。

## 发奖队列

领取成功后不会直接把 `Bukkit.dispatchCommand` 当作最终持久化成功。插件先写入 `dropzone_reward_outbox`，再由 `RewardOutboxWorker` 扫描 PENDING 任务，通过安全调度器执行命令。

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

## 启动诊断

插件完成配置加载、存储初始化和 ProtocolLib 检测后，会自动向控制台输出一次启动诊断。诊断不会发送给玩家，也不会周期性重复输出。

示例：

```text
[DropZone] ===== 启动诊断 =====
[DropZone] 插件版本: 1.0.0-rc.1
[DropZone] 当前启用活动: default
[DropZone] 存储模式: MYSQL
[DropZone] 服务器: survival-1 / 分组 survival
[DropZone] 跨服同步: 开启
[DropZone] 生成模式: DATABASE_LOCK
[DropZone] MySQL: 已连接
[DropZone] 本服活跃奖励点: 3
[DropZone] 发奖队列: 待处理=0 处理中=0 已完成=15 失败=0
[DropZone] ProtocolLib: 已检测
[DropZone] 盔甲架参数: 高度偏移=-0.85 小型=true 标记=false
[DropZone] 发奖队列配置: 启用=true 消费模式=CURRENT_SERVER
[DropZone] 调试开关: 发包=false 可见盔甲架=false
[DropZone] PlaceholderAPI: 已检测
[DropZone] Folia: 否
[DropZone] ==============================
```

如果当前是 `LOCAL_JSON`，MySQL 状态会显示：

```text
[DropZone] MySQL: LOCAL_JSON 模式未启用
```

## 头颅显示高度调整

默认：

```yaml
fake-entity:
  armor-stand-y-offset: -0.85
  armor-stand-small: true
  armor-stand-marker: false
```

- 头颅卡进方块：把 `armor-stand-y-offset` 调高，例如 `-0.65`。
- 头颅太高：把 `armor-stand-y-offset` 调低，例如 `-1.05`。
- 排查显示问题：临时打开 `debug-visible-armorstand: true`。
- 排查发包问题：临时打开 `debug-packets: true`。
- 排查完成后必须关闭 `debug-packets`。

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

本版本没有 `/dz doctor`，也没有 `dropzone.doctor` 权限。

## 从旧版本升级

1. 备份 `plugins/DropZone/`
2. 备份 MySQL 数据库
3. 删除旧 DropZone jar
4. 放入新 jar
5. 确认服务器安装 ProtocolLib
6. 检查 `plugins/DropZone/config.yml`
7. 如果旧配置中存在 `packet-backend: packetevents`，改成 `protocolib`
8. 启动服务器
9. 查看控制台启动诊断
10. 确认没有严重警告

## 正式服上线检查清单

- [ ] `mvn clean package` 成功
- [ ] ProtocolLib 已安装
- [ ] `config.yml` 中 `packet-backend=protocolib`
- [ ] `debug-packets=false`
- [ ] `debug-visible-armorstand=false`
- [ ] `armor-stand-marker=false`
- [ ] MySQL 可连接
- [ ] 启动诊断显示 MySQL 已连接
- [ ] 启动诊断无严重警告
- [ ] `/dz dbstatus` 正常
- [ ] `/dz spawn` 能生成奖励点
- [ ] 玩家靠近能吸附
- [ ] 奖励命令能正常发放
- [ ] 发奖队列没有异常 FAILED
- [ ] 多服 `server.id` 不重复
- [ ] 多服 `group` 配置正确
- [ ] 测试服运行至少 24 小时
- [ ] 正式服上线前备份数据库和配置

## 常见问题

**是否需要 Redis？**  
不需要。当前版本使用 MySQL 权威状态、本地缓存和定时同步，不引入 Redis。

**是否需要 plugin.yml libraries？**  
不需要。本插件是胖包。

**ProtocolLib 是否会被打进 jar？**  
不会。ProtocolLib 仍需单独安装到服务器 `plugins/` 目录。

**LOCAL_JSON 能用于多服吗？**  
不能。LOCAL_JSON 只适合单服测试。
