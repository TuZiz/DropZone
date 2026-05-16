# DropZone

DropZone 是一个 Kotlin + Maven 编写的 Minecraft 插件。插件通过 PacketEvents 给玩家发送客户端假物品实体，不生成真实掉落物，不使用 ProtocolLib、NMS、ArmorStand 或 Display Entity 主实现。奖励点显示为带自定义皮肤的玩家头颅，玩家靠近后会平滑吸附并通过控制台命令发奖。

## 功能

- PacketEvents 假 Item Entity，服务端没有真实掉落实体。
- `config.yml` 配置生成范围、视距、吸附、过期、效果、重载策略、活动资源路径和数据存储路径。
- `action/<活动名>/config.yml` 配置活动显示名、启用状态、清理策略和领取限制。
- `action/<活动名>/heads.yml` 配置头颅 id、MiniMessage 名字、lore、base64 texture、稀有度和权重。
- `action/<活动名>/rewards.yml` 配置稀有度、抽取占比、公告、发光和奖励命令。
- `lang/zh_cn.yml` 管理玩家、管理员和控制台可见文本，全部使用 MiniMessage RGB/Hex 颜色。
- `/dz reload` 异步读取和解析 YAML，运行时只读取不可变配置快照。
- `/dz start <活动名>` 激活指定 `action/<活动名>/` 活动，当前活动默认写入本地 JSON。
- 兼容 Spigot / Paper / Purpur，并通过调度适配器兼容 Folia。

## 安装

1. 执行 `mvn clean package`。
2. 将 `target/DropZone-1.0.0.jar` 放入服务器 `plugins/`。
3. 将 PacketEvents 插件 jar 单独放入服务器 `plugins/`。
4. 启动服务器生成默认配置。
5. 默认 `action/default/heads.yml` 已内置可用 base64 材质；需要换皮肤时替换对应 `texture`。
6. 执行 `/dz reload` 异步重载配置。

## 构建

```bash
mvn clean package
```

当前项目使用本地 `libs/packetevents-spigot-2.12.1-SNAPSHOT.jar` 作为 `system` 编译依赖。DropZone 是薄包，不 shade PacketEvents、Kotlin 或 Adventure；运行时依赖由服务器插件和 `plugin.yml libraries` 提供。

## 兼容说明

- Java 目标字节码：17。
- Bukkit API：Spigot `1.16.5-R0.1-SNAPSHOT`，运行目标为 1.16+。
- Folia：`SchedulerAdapter` 自动检测 `io.papermc.paper.threadedregions.RegionizedServer`，业务层不直接调用 BukkitScheduler。

## 配置文件

- `config.yml`：全局玩法参数、活动资源目录、当前活动状态存储路径。
- `action/default/config.yml`：活动名、启用状态、清理策略和领取限制。
- `action/default/heads.yml`：头颅外观和稀有度绑定。
- `action/default/rewards.yml`：稀有度权重与奖励命令。
- `lang/zh_cn.yml`：玩家消息、管理员消息、列表和 debug 文本。

## 活动目录

每个 `action/<活动名>/` 都是一套独立活动，至少包含：

```text
config.yml
heads.yml
rewards.yml
```

使用命令激活活动：

```text
/dz start default
```

活动目录只放资源配置，不做运行数据存储。当前激活活动默认写入：

```text
plugins/DropZone/data/activity-state.json
```

默认存储模式是 `LOCAL_JSON`。后续要做多服同步时，可以在 `ym.dropzone.storage.ActivityStateStore` 接口下替换为 Redis、MySQL 或消息总线实现。活动根目录、默认活动名、活动配置文件名、heads/rewards 文件名在根 `config.yml` 的 `action` 节点配置；活动状态 JSON 路径在根 `config.yml` 的 `state-storage` 节点配置。

## 头颅材质

活动目录内 `heads.yml` 的 `texture` 填入 Minecraft 皮肤材质 base64。默认文件已配置星星、末影水晶、魔方、宝箱、龙五个可用材质。如果仍写成 `CHANGE_ME_BASE64_TEXTURE`，插件会跳过该头颅并在控制台警告。

## 命令

- `/dropzone reload` 或 `/dz reload`：异步重载配置。
- `/dropzone start <活动名>`：激活指定活动。
- `/dropzone spawn`：手动生成一个奖励点。
- `/dropzone clear`：清理所有假实体。
- `/dropzone list`：列出活跃奖励点。
- `/dropzone debug`：显示运行状态。

## PlaceholderAPI 变量

服务器安装 PlaceholderAPI 后，DropZone 会自动注册 `%dropzone_*%` 变量；未安装 PlaceholderAPI 时插件正常运行。

- `%dropzone_loaded%`：配置快照是否已加载，`1` 或 `0`。
- `%dropzone_status%`：当前活动状态，`enabled`、`disabled` 或 `loading`。
- `%dropzone_activity%`：当前活动 id。
- `%dropzone_activity_name%`：当前活动显示名，已转为纯文本。
- `%dropzone_activity_name_raw%`：当前活动显示名，保留 MiniMessage。
- `%dropzone_activity_enabled%`：当前活动是否启用。
- `%dropzone_active%` / `%dropzone_active_count%`：当前活跃奖励点数量。
- `%dropzone_max_active%`：配置允许的最大活跃奖励点数量。
- `%dropzone_remaining_slots%`：还能生成的奖励点数量。
- `%dropzone_spawn_enabled%`：自动生成是否启用。
- `%dropzone_spawn_interval_seconds%`：自动生成间隔秒数。
- `%dropzone_despawn_seconds%`：奖励点过期秒数。
- `%dropzone_world%`：配置的生成世界。
- `%dropzone_selection_mode%`：奖励抽取模式。
- `%dropzone_language%`：当前语言配置。
- `%dropzone_rarity_count%` / `%dropzone_rarities%`：稀有度数量。
- `%dropzone_usable_rarity_count%` / `%dropzone_usable_rarities%`：可参与抽取的稀有度数量。
- `%dropzone_head_count%` / `%dropzone_heads%`：可用头颅数量。
- `%dropzone_reward_count%` / `%dropzone_rewards%`：奖励数量。
- `%dropzone_activity_count%` / `%dropzone_activities%`：可识别活动目录数量。
- `%dropzone_view_distance%`：假实体可视距离。
- `%dropzone_attract_distance%`：吸附距离。
- `%dropzone_pickup_distance%`：领取距离。

玩家相关最近奖励点变量：

- `%dropzone_nearest_id%`
- `%dropzone_nearest_state%`
- `%dropzone_nearest_distance%`
- `%dropzone_nearest_world%`
- `%dropzone_nearest_x%`
- `%dropzone_nearest_y%`
- `%dropzone_nearest_z%`
- `%dropzone_nearest_reward%`
- `%dropzone_nearest_reward_raw%`
- `%dropzone_nearest_reward_id%`
- `%dropzone_nearest_rarity%`
- `%dropzone_nearest_rarity_raw%`
- `%dropzone_nearest_rarity_id%`
- `%dropzone_nearest_head%`
- `%dropzone_nearest_head_raw%`
- `%dropzone_nearest_head_id%`
- `%dropzone_nearest_visible%`
- `%dropzone_nearest_attractable%`

## 权限

- `dropzone.admin`
- `dropzone.reload`
- `dropzone.start`
- `dropzone.spawn`
- `dropzone.clear`
- `dropzone.list`
- `dropzone.debug`

## 常见问题

**看不到头颅怎么办？**  
确认服务器已安装 PacketEvents，当前活动目录内 `heads.yml` 的材质不是 `CHANGE_ME_BASE64_TEXTURE`，玩家与奖励点在同一世界且距离小于 `fake-entity.view-distance`。

**头颅不飞向玩家怎么办？**  
检查 `fake-entity.attract-distance` 和 `pickup-distance`，并确认玩家与奖励点同世界。

**奖励命令不执行怎么办？**  
命令由控制台执行，不要在配置中写 `/`。同时确认对应经济、抽奖箱等外部命令插件已安装。

**自定义头颅材质不显示怎么办？**  
确认 base64 内容有效。头颅 profile 适配逻辑集中在 `ym.dropzone.util.SkullTextureUtil`。

**Folia 下报错怎么办？**  
确认服务端版本支持 Folia 调度 API，并检查报错是否来自第三方奖励命令。DropZone 自身通过 `SchedulerAdapter` 避免全局主线程假设。

**reload 会不会卡服？**  
不会。YAML 文件读取、解析、校验和权重数据构建都在异步调度中完成，完成后再原子替换运行时快照。
