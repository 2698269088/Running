package top.mcocet.running.pathfinding;

/**
 * 寻路侧的世界访问抽象，把 Baritone 的 {@code CalculationContext}/{@code BlockStateInterface}/
 * {@code MovementHelper} 折叠为一组纯坐标谓词，由 {@link RunningPathWorld} 映射到 Running 的
 * {@code RunningWorld} + {@code BlockNameTable}。
 *
 * <p>这样 {@link PathFinder} 与 {@link Moves} 的算法逻辑完全不依赖任何具体世界实现，
 * 与 Baritone 结构保持一致，便于后续替换更精确的通行/危险判定。</p>
 */
public interface PathWorld {

    /** 所在区块未加载 */
    int UNLOADED = -1;
    /** 空气状态 ID */
    int AIR = 0;

    /** 世界最小 Y（含） */
    int minY();

    /** 世界最大 Y（含），即 {@code minY + height - 1} */
    int maxY();

    /** 查询方块状态 ID；区块未加载返回 {@link #UNLOADED}，超出高度范围返回 {@link #AIR} */
    int stateAt(int x, int y, int z);

    /** 以方块坐标判断所在区块是否已加载（内部按 chunk 坐标换算） */
    boolean isLoaded(int blockX, int blockZ);

    /**
     * 该格是否可供玩家身体占据（脚或头）：空气 / 无实质阻挡为可通行。
     * 保守起见，未知区块与流体首版判为不可通行。
     */
    boolean isPassable(int x, int y, int z);

    /**
     * 该格是否可作为脚下方站立：存在可踩的碰撞顶面且非危险方块（岩浆/火等）。
     */
    boolean isStandable(int x, int y, int z);

    /** 区块已加载且可通行；未加载返回 false（触发分段截断） */
    default boolean isLoadedAndPassable(int x, int y, int z) {
        return isLoaded(x, z) && isPassable(x, y, z);
    }

    /** 区块已加载且可站立；未加载返回 false（触发分段截断） */
    default boolean isLoadedAndStandable(int x, int y, int z) {
        return isLoaded(x, z) && isStandable(x, y, z);
    }

    /** 是否允许在平坦可冲刺处按冲刺计代价 */
    boolean allowSprint();

    /** 允许的最大无伤坠落高度（格），超出判为不可落 */
    int maxFallHeight();

    // ------------------------------------------------------------------
    // 破块查询（首版默认判为不可破，由 RunningPathWorld 结合硬度/工具实现）
    // ------------------------------------------------------------------

    /**
     * 本次计算是否允许把“挖掘方块”作为通路（生成 BREAK 移动并计其代价）。
     * 默认 {@code false}：不主动挖块。由 {@link RunningPathWorld} 依据开关返回。
     */
    default boolean canBreak() {
        return false;
    }

    /** 该格是否为可被挖掘破坏的方块（区块已加载、非空气/流体、可挖且非不可破） */
    default boolean isBreakable(int x, int y, int z) {
        return false;
    }

    /**
     * 破坏该格方块所需 tick（计入路径代价）；不可破或信息缺失返回
     * {@link MoveCosts#COST_INF}。
     */
    default double breakCost(int x, int y, int z) {
        return MoveCosts.COST_INF;
    }

    /**
     * 执行该格挖掘时应切到的快捷栏槽（0..8），使实际耗时匹配 {@link #breakCost} 的最优估计；
     * 徒手最快或无可切换工具时返回 {@code -1}。默认 {@code -1}。
     */
    default int bestToolSlotAt(int x, int y, int z) {
        return -1;
    }
}
