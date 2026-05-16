# DropZone v1.0.0-rc.1 Release Notes

发布日期：2026-05-16  
发布类型：测试服候选版  
目标环境：Minecraft 1.16.5+，Java 17，Bukkit / Paper / Purpur / Folia

## 发布定位

`v1.0.0-rc.1` 是 DropZone 面向测试服上线的候选版本。本版本不新增大型玩法，不重构核心架构，重点是发布前整理、启动自动诊断、中文控制台提示和部署文档补齐。

## 适合谁升级

- 准备把 DropZone 放到测试服验证的服主。
- 已经在使用 MySQL / 多服跨服模式，需要更清晰启动诊断的服主。
- 仍保留旧 PacketEvents 配置，准备切换到 ProtocolLib-only 版本的服主。

## 重要变化

### 启动自动诊断

插件启动完成配置加载、存储初始化和 ProtocolLib 检测后，会自动输出一次中文启动诊断：

```text
[DropZone] ===== 启动诊断 =====
[DropZone] 插件版本: 1.0.0-rc.1
[DropZone] 当前启用活动: default
[DropZone] 存储模式: MYSQL
[DropZone] ProtocolLib: 已检测
[DropZone] PlaceholderAPI: 已检测
[DropZone] Folia: 未检测到
[DropZone] ==============================
```

诊断只输出到控制台，不发送给玩家，也不会每 tick 或玩家加入时重复刷屏。

### ProtocolLib-only

本版本只保留 ProtocolLib 假实体后端。PacketEvents 已不再需要。

如果旧配置中仍有：

```yaml
fake-entity:
  packet-backend: packetevents
```

插件会自动回退到 `PROTOCOLLIB` 并输出中文警告。建议尽快改成：

```yaml
fake-entity:
  packet-backend: protocolib
```

### 版本号与产物

版本统一为：

```text
1.0.0-rc.1
```

构建命令：

```bash
mvn clean package
```

测试服应使用：

```text
target/DropZone-1.0.0-rc.1-all.jar
```

## 升级步骤

1. 备份 `plugins/DropZone/`
2. 备份 MySQL 数据库
3. 删除旧 DropZone jar
4. 放入 `DropZone-1.0.0-rc.1-all.jar`
5. 确认服务器已安装 ProtocolLib
6. 检查 `plugins/DropZone/config.yml`
7. 如果存在 `packet-backend: packetevents`，改成 `protocolib`
8. 启动服务器
9. 查看控制台启动诊断
10. 确认没有严重警告

## 单服推荐配置

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

## 多服推荐配置

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

每台子服 `server.id` 必须唯一。同一组玩法服务器使用相同 `server.group`。lobby 不建议和 survival 共用 group。

## 已知注意事项

- `LOCAL_JSON` 只适合单服测试，不适合多服跨服。
- `reward-outbox.consume-mode=ANY_SERVER` 会被启动诊断标记为风险配置。
- `debug-packets=true` 会输出发包调试日志，排查完成后必须关闭。
- `debug-visible-armorstand=true` 只适合临时排查头颅显示问题。
- `armor-stand-marker=true` 可能影响头颅显示稳定性，默认应保持 `false`。

## 测试服验收建议

- 启动诊断没有严重警告。
- `/dz dbstatus` 正常。
- `/dz spawn` 能生成奖励点。
- 玩家靠近后奖励点能吸附。
- 领取后奖励命令能正常发放。
- 发奖队列没有异常 FAILED。
- 多服环境中 `server.id` 不重复，`server.group` 正确。
- 测试服连续运行至少 24 小时。

## 回滚建议

回滚前先备份当前配置和数据库。回滚 jar 后，确认旧版本是否仍依赖 PacketEvents。如果旧版本需要 PacketEvents，请按旧版本要求恢复相关依赖和配置。
