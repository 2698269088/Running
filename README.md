# Running

面向 [Xinbot](https://github.com/huangdihd/xinbot) 的 **服务端 Bot 移动引擎插件**，实现启发式反作弊（例如Vulcan和Matrix）友好的 20 TPS 物理模拟、A* 寻路与 Baritone 式方块挖掘通路。

## 特性

- **20 TPS 移动引擎** —— 对齐官方客户端物理（重力、摩擦、碰撞盒），每 tick 发送合法位置包，通过 GrimAC 反作弊校验
- **A\* 寻路（移植自 Baritone）** —— 异步状态机 + 专用计算线程；支持走 / 跳 / descend / 对角 / Parkour 跳跨 / 破块穿越等 Moves；跨加载边界自动分段续算
- **统一挖掘机制（对齐 Baritone `positionsToBreak`）** —— 任何前向步跟随前都复校目标站立列（脚格+头格），执行期新出现的挡块（砂砾下落 / 他人放置 / 区块刷新）自动先挖后走，而非怼住卡死
- **移动优先 / 挖掘优先双模式** —— `move` 能绕则绕、挖比绕快才挖；`dig` 挖块代价打折，允许直接挖开方块直线前进到目的地（隐含开启破块与应急挖通）
- **卡住应急挖通** —— 连续无进展时自动从**整个背包（0–35）**挑选最优工具（必要时经库存点击搬迁到快捷栏），朝前进方向挖通开路
- **寻路失败直挖隧道（A* 优先）+ 障碍自动绕行** —— 目标反复无路径时不再原地放弃：先让寻路引擎在已加载区内算**前瞻子目标**安全路线（跳落/坑洞/PARKOUR/BREAK 代价全建模，地面不完整时会绕行而非直接跳下去）照常跟随；A* 彻底无果才朝目标方向逐格直挖兜底（盲走仅限同层/1 格下坠），遇不可挖方块记住障碍列后垂直侧移顺墙绕行，两侧均受阻或绕行超上限才判不可达；期间一出安全路径立刻切回行走
- **无路可踩自动放置搭桥** —— 直挖途中遇到既无方块可踩、又不允许下落（>1 格）的缺口时，自动从**整个背包**选出可放置方块（数量多的优先，必要时先搬到快捷栏），朝相邻实心面（优先脚下块的忍者桥放法，其次垫脚 UP 面）点击铺地板，确认生效后再踏上新路面继续前进；无锚点/无方块/超预算则回退绕行或等待，绝不乱摔
- **背包 & 工具系统** —— 完整背包跟踪 + items.json 解析建模（7 档 × 4 类 = 28 种挖掘工具），支持 `inventory` 命令查看/移动物品
- **反作弊适配** —— 挖掘时长按实际手持自主计时（只慢不快，防止 fastbreak）；TickTimer / 朝向一致性合规
- **命令系统** —— `pos` `block` `look` `move` `goto` `inventory` `act dig/place` 等控制台指令

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven + Shade |
| Bot 框架 | Xinbot 2.4.3-RELEASE |
| 协议层 | MCProtocolLib（由 Xinbot 封装） |
| 运行环境 | Minecraft 1.21.11（ViaVersion 协商） |
| 数据源 | minecraft-data `blocks.json` + `items.json` + `collision_shapes.bin` |

## 快速开始

```
# 查看坐标与朝向
pos

# A* 寻路到目标（默认跟随配置的寻路模式）
goto 100 64 200

# 单次指定模式：挖掘优先（直挖前进）/ 移动优先（能绕则绕）
goto dig 100 64 200
goto move 100 64 200

# 持续跟随玩家（自动重算，move stop 停止）
goto follow Steve

# 查看背包 / 移动物品（0-8 快捷栏，9-35 主背包）
inventory
inventory quick 20
inventory hotbar 20 3

# 停止移动
move stop

# 手动测试挖掘/放置
act dig 100 63 200
act place 100 64 200
```

## 配置

插件启动时从 **工作目录 / `Running/config.properties`** 加载（缺失自动生成默认模板，修改后重启生效）：

| 键 | 默认 | 说明 |
|---|---|---|
| `pathfinding.allow-sprint` | `true` | 平坦同层路径疾跑 |
| `pathfinding.allow-break` | `true` | 允许 BREAK 移动开路 |
| `pathfinding.mode` | `move` | 寻路模式：`move` 移动优先 / `dig` 挖掘优先（隐含允许破块） |
| `pathfinding.stuck-dig-recovery` | `true` | 卡住时自动取背包工具应急挖通（`dig` 模式恒开启） |
| `pathfinding.allow-place` | `true` | 直挖无路可踩的缺口处允许放置方块搭桥（需背包有可放置方块） |
| `pathfinding.allow-parkour` | `true` | 冲刺 / 步行跳跨 1~3 格空隙 |
| `pathfinding.max-fall-height` | `3` | 允许的最大无伤坠落高度（格） |
| `pathfinding.arrive-radius` | `0.5` | 航点到达判定半径（格） |
| `physics.auto-jump` | `true` | 撞一格边缘自动起跳 |

## 数据来源

- **blocks.json / items.json**：[prismarinejs/minecraft-data](https://github.com/PrismarineJS/minecraft-data) → `data/pc/1.21/java/`
- **碰撞形状**：Minecraft 内置数据包 `collision_shapes` 提取
- **工具速度**：原版 `ToolMaterial.java` 硬编码常量（WOOD=2 / STONE=4 / COPPER=5 / IRON=6 / DIAMOND=8 / GOLD=12 / NETHERITE=9）
