# Running

一个基于XinBot和MovementSync的自动寻路插件，集成了Baritone路径规划引擎和MovementSync位置同步系统。

## 功能特性

- 🚀 **智能路径规划**：使用Baritone引擎进行高效的路径搜索
- 📍 **精准位置同步**：与MovementSync插件深度集成，实时获取玩家位置
- 🎯 **多种目标支持**：支持XZ坐标导航和XYZ精确位置导航
- 📊 **路径质量评估**：内置路径质量分析和智能优化机制
- 🔧 **分段导航**：自动处理长距离路径，支持智能分段执行

## 依赖要求

### 必需前置插件
- **MovementSync** - 位置同步插件（必需）
  - 提供精确的位置信息获取
  - 处理实体同步和坐标更新

### 技术依赖
- Java 17 或更高版本
- Maven 3.6+
- XinBot机器人框架

## 安装说明

1. 确保已安装MovementSync插件
2. 编译Running插件：
   ```bash
   mvn clean package
   ```
3. 将生成的jar文件放置到机器人插件目录
4. 启动机器人并加载插件

## 使用方法

### 基本命令

```
/rung goto <x> <z>          - 导航到指定XZ坐标
/rung goto <x> <y> <z>      - 导航到精确的XYZ位置
/rung stop                  - 停止当前路径执行
/rung whereami              - 显示当前位置信息
/rung help                  - 显示帮助信息
```

### 使用示例

```bash
# 导航到坐标(100, 50)
/rung goto 100 50

# 导航到精确位置(100, 64, 50)
/rung goto 100 64 50

# 停止当前导航
/rung stop
```

## 技术架构

### 核心组件

- **MovementAdapter**：位置适配器，与MovementSync插件交互
- **PathExecutor**：路径执行器，处理路径规划和执行逻辑
- **PathQualityAssessor**：路径质量评估器，分析路径优劣
- **RunCommandExecutor**：命令处理器，解析和执行用户指令

### 工作流程

1. 用户输入导航命令
2. 插件获取当前位置（通过MovementSync）
3. 使用Baritone引擎计算最优路径
4. 评估路径质量并进行优化
5. 执行路径移动并实时监控

## 配置说明

插件使用默认配置即可正常工作。如需自定义参数，请修改相关类中的常量值。

## 故障排除

### 常见问题

**Q: 出现"当前位置为(0,0,0)"错误**
A: 确保MovementSync插件已正确加载且机器人已连接到服务器

**Q: 路径规划失败**
A: 检查目标坐标是否合理，尝试使用分段导航功能

**Q: 移动过程中卡住**
A: 使用`/rung stop`命令停止当前执行，重新规划路径

### 调试信息

插件会在控制台输出详细的调试信息，包括：
- 位置获取状态
- 路径规划过程
- 执行进度跟踪

## 开发说明

### 项目结构
```
src/main/java/top/mcocet/running/
├── commands/           # 命令处理模块
├── movement/           # 移动和位置处理
├── pathfinding/        # 路径规划核心
│   ├── core/          # 核心路径算法
│   ├── goals/         # 目标定义
│   └── utils/         # 工具类
└── Running.java       # 主插件类
```

### 编译构建
```bash
# 清理并编译
mvn clean compile

# 打包生成jar
mvn package
```

## 许可证

本项目采用GNU General Public License v3.0许可证。

```
Copyright (C) 2026 Running Plugin Development Team

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

## 开发者

- [MCOCET](https://github.com/2698269088/)
- [huangdihd](https://github.com/huangdihd/)

欢迎提交Issue和Pull Request来改进项目。

## 致谢

- [Baritone](https://github.com/cabaletta/baritone) - 路径规划引擎
- [MovementSync](https://github.com/huangdihd/MovementSync) - 位置同步插件
- [XinBot](https://github.com/huangdihd/XinBot) - XinBot机器人框架

---
*Running Plugin - 让XinBot智能移动*