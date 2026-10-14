package top.mcocet.running.pathfinding;

import top.mcocet.running.world.item.ToolModel;

/**
 * 忠实复刻原版（1.21.4）玩家破坏方块所需 tick 的计算链：
 * <pre>
 *   Item.getDestroySpeed   = tool != null ? tool.getMiningSpeed(state) : 1.0
 *   Player.getDestroySpeed = base(>1 时加效率附魔) × ... ；不在地面时 ÷5
 *   Player.hasCorrectToolForDrops = !state.requiresCorrectToolForDrops()
 *                                      || (tool != null && tool.isCorrectForDrops(state))
 *   BlockBehaviour.getDestroyProgress = speed / hardness / (correctToolForDrops ? 30 : 100)
 *   破块 tick 数 = ceil(1 / progress)
 * </pre>
 *
 * <p>刻意不引入创造模式、效率/急迫/疲劳效果、水下挖掘加成等（默认取恒等/关闭），
 * 因为这些取决于运行时状态；核心公式与原版逐字对齐，保证 Grim 的 fastbreak 校验
 * 下破坏耗时与服务端一致。附魔与状态加成留作后续注入。</p>
 */
public final class MiningCalculator {

    private MiningCalculator() {
    }

    /**
     * @param tool               手持工具的 {@link ToolModel}；徒手/无工具组件传 {@code null}
     * @param blockName          目标方块名（供工具规则的名称匹配）
     * @param blockCategory      目标方块采集族（{@code BlockNameTable.TOOL_*}）
     * @param hardness           目标方块硬度（{@code <0} 或 {@code NaN} 视为不可破坏）
     * @param requiresCorrectTool 目标方块是否需要正确工具才有掉落
     * @param onGround           玩家是否着地（腾空挖掘速度 ÷5，对齐原版）
     * @return 破坏所需 tick；不可破坏返回 {@link MoveCosts#COST_INF}
     */
    public static double breakTicks(ToolModel tool, String blockName, byte blockCategory,
                                    float hardness, boolean requiresCorrectTool, boolean onGround) {
        if (Float.isNaN(hardness) || hardness < 0) {
            return MoveCosts.COST_INF; // 未知或不可破坏（基岩、末地传送门框架等）
        }
        // Item.getDestroySpeed → Player.getDestroySpeed（忽略附魔/属性，仅处理着地因子）
        float speed = tool != null ? tool.getMiningSpeed(blockName, blockCategory) : 1.0F;
        if (!onGround) {
            speed /= 5.0F;
        }
        if (speed <= 0.0F) {
            return MoveCosts.COST_INF;
        }
        if (hardness == 0.0F) {
            return 1; // 硬度 0 近似瞬破，至少占一个 tick
        }
        boolean correctForDrops = !requiresCorrectTool
                || (tool != null && tool.isCorrectForDrops(blockName, blockCategory));
        double divisor = correctForDrops ? 30.0 : 100.0;
        double perTick = speed / hardness / divisor;
        if (perTick <= 0.0 || Double.isNaN(perTick)) {
            return MoveCosts.COST_INF;
        }
        return Math.ceil(1.0 / perTick);
    }
}
