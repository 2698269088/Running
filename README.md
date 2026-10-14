# Running

面向 [Xinbot](https://github.com/huangdihd/xinbot) 的 **服务端 Bot 移动引擎插件**，实现启发式反作弊（例如Vulcan和Matrix）友好的 20 TPS 物理模拟、A* 寻路与 Baritone 式方块挖掘通路。

## 特性

- **20 TPS 移动引擎** —— 对齐官方客户端物理（重力、摩擦、碰撞盒），每 tick 发送合法位置包，通过 GrimAC 反作弊校验
- **A\* 寻路（移植自 Baritone）** —— 异步状态机 + 专用计算线程；支持走/跳/ descend/ 对角/ 破块穿越等 Moves
- **方块挖掘通路（Stage 4）** —— 寻路将 `breakCost`（工具×硬度→tick）纳入代价，路径自然偏好"绕硬块 / 走最快可挖通道"；执行时自动切换最优工具并逐 tick 挖掘
- **背包 & 工具系统** —— 完整背包跟踪 + items.json 解析建模（7 档 × 4 类 = 28 种挖掘工具）
- **Grim 安全** —— 挖掘时长按实际手持自主计时（只慢不快，绝不 fastbreak）；TickTimer / 朝向一致性合规
- **命令系统** —— `pos` `block` `look` `move` `goto` `act dig/place` 等控制台指令

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven + Shade |
| Bot 框架 | Xinbot 2.4.3-RELEASE |
| 协议层 | MCProtocolLib（由 Xinbot 封装） |
| 运行环境 | Minecraft 1.21.11（ViaVersion 协商） |
| 数据源 | minecraft-data `blocks.json` + `items.json` + `collision_shapes.bin` |

## 项目结构

```
src/main/java/top/mcocet/running/
├── bot/               # 移动引擎、物理模拟、BlockActionGoal
├── commands/          # 控制台命令实现
├── listeners/         # 连接/世界包监听
├── math/              # 向量、旋转工具
├── pathfinding/       # A* 寻路核心（Moves, PathFinder, PathingGoal, ...）
├── world/             # 世界缓存、方块表、背包、物品注册表
├── Main.java          # 插件入口
└── RunningCore.java   # 生命周期 & 组件装配

src/main/resources/
├── blocks.json            # 29671 方块状态数据
├── collision_shapes.bin   # 碰撞形状表
├── items.json             # 1505 物品 + 28 工具建模
├── commands.yml           # 命令注册
└── plugin.yml             # Xinbot 插件描述
```

## 快速开始

### 前置条件

1. [Xinbot](https://github.com/huangdihd/xinbot) 2.4.3+ 已部署并可连接目标服务器
2. JDK 17+
3. Maven 3.8+

### 构建

```bash
mvn clean package
```

产物：`target/Running-1.1.0.jar`

### 安装

将 jar 放入 Xinbot 的 `plugins/` 目录，启动 Xinbot 即自动加载。

### 使用

```
# 查看坐标与朝向
pos

# A* 寻路到目标（自动破块穿越）
goto 100 64 200

# 停止移动
move stop

# 手动测试挖掘/放置
act dig 100 63 200
act place 100 64 200
```

## 开发路线

- [x] Stage 1：方块数据基础（hardness / diggable / toolCategory / requiresTool）
- [x] Stage 2：背包 & 工具模型（items.json + ToolMaterial 速度）
- [x] Stage 3：破块代价数学（MiningCalculator）
- [x] Stage 4：破块穿越（BREAK Moves + A* 代价 + PathingGoal 逐 tick 执行）
- [ ] Stage 5：方块放置执行（搭桥 / 搭柱）
- [ ] Stage 6：寻路超时脱困（偏置落点 + Favoring 加权）

## 数据来源

- **blocks.json / items.json**：[prismarinejs/minecraft-data](https://github.com/PrismarineJS/minecraft-data) → `data/pc/1.21/java/`
- **碰撞形状**：Minecraft 内置数据包 `collision_shapes` 提取
- **工具速度**：原版 `ToolMaterial.java` 硬编码常量（WOOD=2 / STONE=4 / COPPER=5 / IRON=6 / DIAMOND=8 / GOLD=12 / NETHERITE=9）

## 许可

本项目仅供学习与研究用途。
