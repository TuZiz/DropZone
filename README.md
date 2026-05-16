# DropZone

DropZone 是一个 Minecraft 奖励点插件。它会在配置区域内随机生成客户端可见的假头颅奖励点，玩家靠近后头颅会飞向玩家，领取后通过控制台命令发放奖励。

插件使用 PacketEvents 显示假物品实体，不生成真实掉落物，不使用 ProtocolLib、NMS、ArmorStand 或 Display Entity。

## 功能

- 随机生成自定义皮肤头颅奖励点。
- 支持 `MAX_RADIUS` 和 `BOX` 两种生成区域。
- 支持生成高度、区块加载策略、地面/空气/水/岩浆检查。
- 玩家进入可视距离后显示假实体。
- 玩家进入吸附距离后头颅平滑飞向玩家。
- 领取后自动销毁假实体并执行奖励命令。
- 支持稀有度、权重、公告和头颅发光。
- 支持多个活动目录，通过 `/dz start <活动名>` 切换活动。
- 每个活动可单独配置生成世界和生成范围。
- 支持领取次数限制、领取冷却、禁止重复获得同一奖励。
- 支持音效、粒子、Title、ActionBar。
- 支持 PlaceholderAPI 变量。
- 支持 Spigot / Paper / Purpur / Folia。

## 安装

1. 将 `DropZone-1.0.0.jar` 放入服务器 `plugins/` 目录。
2. 将 PacketEvents 插件也放入服务器 `plugins/` 目录。
3. 如果需要 PlaceholderAPI 变量，将 PlaceholderAPI 插件放入服务器 `plugins/` 目录。
4. 启动服务器生成默认配置。
5. 修改 `config.yml` 和 `action/default/` 下的活动文件。
6. 执行 `/dz reload` 重载配置。

DropZone jar 已内置 Kotlin、Adventure、MySQL、SQLite、PostgreSQL 驱动；PacketEvents 仍需作为独立插件安装。

## 构建

```bash
mvn clean package
```

构建产物在：

```text
target/DropZone-1.0.0.jar
```

该 jar 会包含 Kotlin、Adventure 和常用数据库驱动，不包含 PacketEvents、PlaceholderAPI 或服务端 API。

## 配置文件

```text
plugins/DropZone/config.yml
plugins/DropZone/lang/zh_cn.yml
plugins/DropZone/action/default/config.yml
plugins/DropZone/action/default/heads.yml
plugins/DropZone/action/default/rewards.yml
```

- `config.yml`：全局吸附距离、奖励点表现、效果和运行策略。
- `lang/zh_cn.yml`：所有玩家可见消息。
- `action/<活动名>/config.yml`：活动开关、领取规则、活动专属生成世界和范围。每个活动都必须配置 `spawn-region`。
- `action/<活动名>/heads.yml`：头颅材质、显示名、lore、稀有度和权重。
- `action/<活动名>/rewards.yml`：稀有度、奖励权重、公告和奖励命令。

## 活动目录

每个活动都是一个独立文件夹：

```text
action/default/
  config.yml
  heads.yml
  rewards.yml
```

新增活动时复制 `default` 文件夹并改名，例如：

```text
action/summer/
```

然后使用：

```text
/dz start summer
```

## 命令

```text
/dropzone reload
/dropzone start <活动名>
/dropzone spawn
/dropzone clear
/dropzone list
/dropzone debug
```

别名：

```text
/dz
```

## 权限

```text
dropzone.admin
dropzone.reload
dropzone.start
dropzone.spawn
dropzone.clear
dropzone.list
dropzone.debug
```

## PlaceholderAPI

安装 PlaceholderAPI 后可使用 `%dropzone_*%` 变量。

常用变量：

```text
%dropzone_loaded%
%dropzone_status%
%dropzone_activity%
%dropzone_activity_name%
%dropzone_active_count%
%dropzone_max_active%
%dropzone_remaining_slots%
%dropzone_spawn_enabled%
%dropzone_rarity_count%
%dropzone_head_count%
%dropzone_reward_count%
%dropzone_nearest_distance%
%dropzone_nearest_reward%
%dropzone_nearest_rarity%
%dropzone_nearest_head%
```

## 奖励命令变量

奖励命令和消息支持：

```text
%player%
%uuid%
%reward_id%
%reward%
%rarity_id%
%rarity%
%head_id%
%head%
%x%
%y%
%z%
%world%
%prefix%
```

示例：

```yaml
commands:
  - "eco give %player% 100"
  - "give %player% diamond 3"
```

## 头颅材质

`heads.yml` 中的 `texture` 使用 Minecraft 头颅 base64 材质。默认配置已包含可用示例材质，可以直接运行测试。

如果材质仍是 `CHANGE_ME_BASE64_TEXTURE`，该头颅会被跳过。

## 领取规则

活动配置支持：

```yaml
rules:
  clear-active-on-start: true
  max-claims-per-player: 0
  claim-cooldown-seconds: 0
  allow-repeat-rewards: true
```

- `max-claims-per-player: 0` 表示不限制领取次数。
- `claim-cooldown-seconds: 0` 表示不限制领取冷却。
- `allow-repeat-rewards: false` 表示同一活动中同一玩家不能重复获得同一个 reward id。

## 常见问题

**看不到头颅怎么办？**  
确认服务器已安装 PacketEvents，并确认玩家和奖励点在同一世界且距离小于 `fake-entity.view-distance`。

**奖励命令不执行怎么办？**  
奖励命令由控制台执行，配置中不要写开头的 `/`。同时确认经济、抽奖箱等第三方命令插件已安装。

**reload 会不会卡服？**  
不会。配置文件读取、解析和校验走异步流程，运行时逻辑只读取内存快照。
