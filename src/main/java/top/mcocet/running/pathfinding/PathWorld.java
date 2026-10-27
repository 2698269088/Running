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

    /**
     * 是否允许 Parkour（冲刺/步行跳跨 1–3 格空隙）。默认 {@code false}，
     * 由 {@link RunningPathWorld} 依据开关返回。
     */
    default boolean allowParkour() {
        return false;
    }

    // ------------------------------------------------------------------
    // 鞘翅滑翔查询（GLIDE 移动；默认全部关闭，由 RunningPathWorld 依据开关/装备返回）
    // ------------------------------------------------------------------

    /**
     * 本次计算是否允许生成 GLIDE 移动（需配置开启且胸槽已装鞘翅，
     * 由 {@link RunningPathWorld} 判定；还需 {@link #glideTable()} 非空）。
     */
    default boolean allowGlide() {
        return false;
    }

    /**
     * 滑翔弹道预计算表（{@link GlideTable#build} 按巡航俯仰角/自动补弹模型生成）；
     * 不支持滑翔时为 {@code null}。
     */
    default GlideTable glideTable() {
        return null;
    }

    /** 单次滑翔边最大水平跨度（格）。 */
    default int glideMaxDistance() {
        return 64;
    }

    /** 单次滑翔边预估火箭消耗上限（超出截断该边）。 */
    default int glideRocketBudget() {
        return 0;
    }

    /**
     * 该格作为站立面时对“走一格”代价的乘子（&ge;1）：平地 / 普通固体为 1，
     * 灵魂沙等减速方块 {@code >1}。默认 1。
     */
    default double surfaceCostMultiplier(int x, int y, int z) {
        return 1.0;
    }

    /** 该格是否为可攀爬方块（梯子 / 藤蔓）：攀爬格不作为起跳蹬踏面。默认 false。 */
    default boolean isClimbable(int x, int y, int z) {
        return false;
    }

    /**
     * 该格是否为危险方块（岩浆 / 火 / 岩浆块等）：既不可通行也不可站立，
     * Parkour 落点越冲也需避开。默认 {@code false}。
     */
    default boolean isDangerousAt(int x, int y, int z) {
        return false;
    }

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

    /**
     * 寻路模式是否为“挖掘优先”：{@code true} 时挖块耗时在代价中打折
     * （见 {@link MoveCosts#DIG_PRIORITY_BREAK_DISCOUNT}），使 A* 倾向直挖穿过障碍
     * 而非绕路；且隐含允许破块（即使 allow-break 关闭）。默认 {@code false}，
     * 即“移动优先”：仅在挖比绕更省时才挖。由 {@link RunningPathWorld} 依据配置返回。
     */
    default boolean digPriority() {
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

    /**
     * 为“挖通该格”规划最优工具：在<b>整个背包（0..35）</b>中挑选使破坏耗时最短的物品，
     * 并给出执行动作（手持哪个快捷槽、是否需要先从主背包把工具换到快捷栏）。相较仅看
     * 快捷栏的 {@link #bestToolSlotAt}，本方法能利用背包内的合适工具，避免徒手慢挖/判定不可破
     * 而卡住。默认返回 {@link DigTool#unbreakable()}（不支持的工具感知由具体实现提供）。
     */
    default DigTool planDigTool(int x, int y, int z) {
        return DigTool.unbreakable();
    }

    // ------------------------------------------------------------------
    // 放置搭桥查询（无路可踩的缺口用背包方块铺地板；默认不支持）
    // ------------------------------------------------------------------

    /**
     * 本次寻路是否允许“放置搭桥”：路径上没有可踩方块时用背包里的方块物品
     * 放置出一个站立面（USE_ITEM_ON 合法交互，非破坏）。默认 {@code false}，
     * 由 {@link RunningPathWorld} 依据开关返回。
     */
    default boolean canPlace() {
        return false;
    }

    /**
     * 为“搭桥放置”选物品：在<b>整个背包（0..35）</b>中挑选名称对应“放置后可站立方块”
     * （有碰撞、非流体、非危险、非重力沙砾）的堆，数量多者优先（数量决定可铺桥长）；
     * 若其位于主背包则给出搬迁方案。无可用方块/信息缺失返回 {@link PlaceTool#none()}。
     */
    default PlaceTool planPlaceBlock() {
        return PlaceTool.none();
    }

    /**
     * 该格是否可被放置方块替换（空气 / 水 / 岩浆 / 草 / 花等无碰撞方块）。默认仅空气，
     * 由 {@link RunningPathWorld} 依据方块表返回。用于搭桥放置时判断目标格能否被直接覆盖，
     * 不能替换时需先挖开再放置。
     */
    default boolean isReplaceable(int x, int y, int z) {
        return stateAt(x, y, z) == AIR;
    }

    /**
     * 该方块在朝向 {@code face}（{@code 0=北,1=南,2=西,3=东,4=下,5=上}，对齐
     * {@code BlockNameTable.FACE_*}）的面上是否为完整面（可附着放置方块）。
     * 默认 {@code false}，由 {@link RunningPathWorld} 依据方块表返回；用于放置搭桥时
     * 排除楼梯/半砖等“有碰撞但面不完整”的方块，避免向斜面发送无效放置包。
     */
    default boolean isFaceSturdy(int x, int y, int z, int face) {
        return false;
    }

    /**
     * 该格是否为水（含水流/水源，不含岩浆）。默认 {@code false}，由 {@link RunningPathWorld}
     * 依据方块表返回。用于游泳寻路时判定可通行水体。
     */
    default boolean isWater(int x, int y, int z) {
        return false;
    }

    /**
     * 该格是否为岩浆。默认 {@code false}，由 {@link RunningPathWorld} 依据方块表返回。
     * 岩浆始终视为危险（不可通行/不可站立），但单独查询可用于特殊处理。
     */
    default boolean isLava(int x, int y, int z) {
        return false;
    }

    /**
     * 该列从 {@code y} 向上到水面是否均为水（含当前格）。用于判定“当前位置是否在水中”
     * 以及“向上能否游出水面”。默认 {@code false}。
     */
    default boolean isWaterColumn(int x, int y, int z) {
        return false;
    }

    /**
     * 该列从 {@code y} 向上找到第一个非水格（空气/实心块），返回其 Y 坐标；
     * 若到世界顶部仍为水则返回 {@code maxY+1}。用于判定水面位置。
     */
    default int waterSurfaceY(int x, int y, int z) {
        return maxY() + 1;
    }
}
