/*
 * 本文件的结构与代价模型移植自 Baritone（LGPL-3.0）的
 * baritone.pathing.movement.Moves 及 movements/MovementTraverse|Ascend|Descend|Downward|Diagonal|Parkour。
 * 在对角（Diagonal，含升/平/降与表面减速）与 Parkour（跳跨空隙）上对齐 Baritone 的代价判定，
 * 并保留 BREAK 的对角变体；去掉了放置 / 梯子攀爬 / 涉水 / 霜行等尚不适用本无头条实现的分支，
 * 并把 MC 的 {@code canWalkThrough}/{@code canWalkOn} 替换为 {@link PathWorld} 的
 * {@link PathWorld#isPassable}/{@link PathWorld#isStandable}。
 */
package top.mcocet.running.pathfinding;

/**
 * 基础移动枚举子集。每个 move 携带名义偏移（供 A* 做区块加载 / 边界判定）与
 * {@link #apply(PathWorld, int, int, int, MutableMoveResult)}：在 {@link PathWorld} 上计算
 * 落点与代价写入结果对象。
 *
 * <p>{@code dynamicY=true} 的 move（{@code DOWNWARD}/{@code DESCEND_*}/{@code DIAGONAL_*}/
 * {@code PARKOUR_*}）落点 Y 依地形动态确定（可能是落 1 格，也可能是多格坠落，或对角上/下
 * 一格，或 parkour 抬高一级）；{@code dynamicXZ=true} 的 {@code PARKOUR_*} 落点 XZ 亦随空隙
 * 宽度在 2..4 格间动态确定。其余 move 落点为名义偏移。dynamic 的 move 必须覆写
 * {@link #apply}，用 {@link MutableMoveResult} 写回真实落点与代价，与 Baritone 对这些动作
 * 的处理一致（{@code MovementDiagonal}/{@code MovementParkour}）。</p>
 */
public enum Moves {

    DOWNWARD(0, -1, 0, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            // 原地直落：正下方 (x,y-1) 起向下找落脚面，列内须畅通且不超过 maxFallHeight
            fall(w, x, y, z, x, z, res);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return traverseCost(w, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return traverseCost(w, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return traverseCost(w, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return traverseCost(w, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, 1, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return ascendCost(w, x, y, z, x, z - 1);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }
    },

    ASCEND_SOUTH(0, 1, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return ascendCost(w, x, y, z, x, z + 1);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }
    },

    ASCEND_EAST(1, 1, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return ascendCost(w, x, y, z, x + 1, z);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }
    },

    ASCEND_WEST(-1, 1, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return ascendCost(w, x, y, z, x - 1, z);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            fall(w, x, y, z, x, z - 1, res);
        }
    },

    DESCEND_SOUTH(0, -1, 1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            fall(w, x, y, z, x, z + 1, res);
        }
    },

    DESCEND_EAST(1, -1, 0, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            fall(w, x, y, z, x + 1, z, res);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            fall(w, x, y, z, x - 1, z, res);
        }
    },

    DIAGONAL_NORTHEAST(1, 0, -1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            diagonal(w, x, y, z, x + 1, z - 1, res);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            diagonal(w, x, y, z, x - 1, z - 1, res);
        }
    },

    DIAGONAL_SOUTHEAST(1, 0, 1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            diagonal(w, x, y, z, x + 1, z + 1, res);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, 1, false, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            diagonal(w, x, y, z, x - 1, z + 1, res);
        }
    },

    BREAK_NORTH(0, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return breakTraverseCost(w, x, y, z, x, z - 1);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_SOUTH(0, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return breakTraverseCost(w, x, y, z, x, z + 1);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_EAST(1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return breakTraverseCost(w, x, y, z, x + 1, z);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_WEST(-1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return breakTraverseCost(w, x, y, z, x - 1, z);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_NORTHEAST(1, 0, -1) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            breakDiagonal(w, x, y, z, x + 1, z - 1, res);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_NORTHWEST(-1, 0, -1) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            breakDiagonal(w, x, y, z, x - 1, z - 1, res);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_SOUTHEAST(1, 0, 1) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            breakDiagonal(w, x, y, z, x + 1, z + 1, res);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    BREAK_SOUTHWEST(-1, 0, 1) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            breakDiagonal(w, x, y, z, x - 1, z + 1, res);
        }

        @Override
        public boolean isBreak() {
            return true;
        }
    },

    PARKOUR_NORTH(0, 0, -1, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            parkour(w, x, y, z, 0, -1, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isParkour() {
            return true;
        }
    },

    PARKOUR_SOUTH(0, 0, 1, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            parkour(w, x, y, z, 0, 1, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isParkour() {
            return true;
        }
    },

    PARKOUR_EAST(1, 0, 0, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            parkour(w, x, y, z, 1, 0, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isParkour() {
            return true;
        }
    },

    PARKOUR_WEST(-1, 0, 0, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            parkour(w, x, y, z, -1, 0, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isParkour() {
            return true;
        }
    },

    PILLAR(0, 1, 0) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            pillar(w, x, y, z, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isPillar() {
            return true;
        }
    },

    BRIDGE_NORTH(0, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return bridgeCost(w, x, y, z, x, z - 1);
        }

        @Override
        public boolean isBridge() {
            return true;
        }
    },

    BRIDGE_SOUTH(0, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return bridgeCost(w, x, y, z, x, z + 1);
        }

        @Override
        public boolean isBridge() {
            return true;
        }
    },

    BRIDGE_EAST(1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return bridgeCost(w, x, y, z, x + 1, z);
        }

        @Override
        public boolean isBridge() {
            return true;
        }
    },

    BRIDGE_WEST(-1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return bridgeCost(w, x, y, z, x - 1, z);
        }

        @Override
        public boolean isBridge() {
            return true;
        }
    },

    SWIM_NORTH(0, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimCost(w, x, y, z, x, z - 1);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_SOUTH(0, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimCost(w, x, y, z, x, z + 1);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_EAST(1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimCost(w, x, y, z, x + 1, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_WEST(-1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimCost(w, x, y, z, x - 1, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_UP(0, 1, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimUpCost(w, x, y, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }

        @Override
        public boolean jumpUp() {
            return true;
        }
    },

    SWIM_DOWN(0, -1, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimDownCost(w, x, y, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_TO_SHORE_NORTH(0, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimToShoreCost(w, x, y, z, x, z - 1);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_TO_SHORE_SOUTH(0, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimToShoreCost(w, x, y, z, x, z + 1);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_TO_SHORE_EAST(1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimToShoreCost(w, x, y, z, x + 1, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    SWIM_TO_SHORE_WEST(-1, 0, 0) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return swimToShoreCost(w, x, y, z, x - 1, z);
        }

        @Override
        public boolean isSwim() {
            return true;
        }
    },

    // ---- 鞘翅滑翔（GLIDE）：名义偏移 ×8 仅用于区块边界预判，真实落点由弹道表扫描得出 ----

    GLIDE_NORTH(0, -1, -8, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            glide(w, x, y, z, 0, -1, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isGlide() {
            return true;
        }
    },

    GLIDE_SOUTH(0, -1, 8, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            glide(w, x, y, z, 0, 1, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isGlide() {
            return true;
        }
    },

    GLIDE_EAST(8, -1, 0, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            glide(w, x, y, z, 1, 0, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isGlide() {
            return true;
        }
    },

    GLIDE_WEST(-8, -1, 0, true, true) {
        @Override
        public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
            glide(w, x, y, z, -1, 0, res);
        }

        @Override
        public boolean jumpUp() {
            return true;
        }

        @Override
        public boolean isGlide() {
            return true;
        }
    };

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;
    public final boolean dynamicXZ;
    public final boolean dynamicY;

    private static final double SQRT_2 = Math.sqrt(2);

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    /**
     * 计算本移动从 (x,y,z) 出发的落点与代价写入 {@code res}。非 dynamicY 的 move 直接用名义
     * 偏移 + {@link #cost}；dynamicY 的 move 覆写本方法。
     */
    public void apply(PathWorld w, int x, int y, int z, MutableMoveResult res) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("dynamic 移动必须覆写 apply");
        }
        res.x = x + xOffset;
        res.y = y + yOffset;
        res.z = z + zOffset;
        res.cost = cost(w, x, y, z);
    }

    /** 静态代价 move 覆写本方法；dynamic 覆写 apply 的 move 无需实现 */
    public double cost(PathWorld w, int x, int y, int z) {
        throw new UnsupportedOperationException("move 必须覆写 cost 或 apply");
    }

    /** 执行时是否需要主动起跳（上台阶）；供 {@link PathingGoal} 注入跳跃 */
    public boolean jumpUp() {
        return false;
    }

    /** 本步是否需要先挖掘方块打通（供 {@link PathingGoal} 进入破块子阶段） */
    public boolean isBreak() {
        return false;
    }

    /** 本步是否为 Parkour 跳跨空隙（供 {@link PathingGoal} 进入多 tick 跳跃跟随状态机） */
    public boolean isParkour() {
        return false;
    }

    /** 本步是否为原地向上搭建（供 {@link PathingGoal} 进入放置/攀爬逻辑） */
    public boolean isPillar() {
        return false;
    }

    /** 本步是否为放置方块搭桥（供 {@link PathingGoal} 先铺地板再走） */
    public boolean isBridge() {
        return false;
    }

    /** 本步是否为游泳（供 {@link PathingGoal} 进入游泳跟随逻辑） */
    public boolean isSwim() {
        return false;
    }

    /** 本步是否为鞘翅滑翔（供 {@link PathingGoal} 进入起滑/巡航/着陆状态机） */
    public boolean isGlide() {
        return false;
    }

    // ------------------------------------------------------------------
    // 代价规则（首版：基础移动）
    // ------------------------------------------------------------------

    /** 横向走一格：dest 脚+头可通行、dest 下方可站立 → WALK（允许冲刺时乘冲刺系数）。 */
    private static double traverseCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        if (!w.isLoadedAndPassable(destX, y, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndStandable(destX, y - 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        double cost = MoveCosts.WALK_ONE_BLOCK_COST;
        // 灵魂沙等减速站立面抬高走格代价（对齐 Baritone surfaceFactor 乘子；isStandable 已排除危险面）
        cost *= w.surfaceCostMultiplier(destX, y - 1, destZ);
        if (w.allowSprint()) {
            cost *= MoveCosts.SPRINT_MULTIPLIER;
        }
        return cost;
    }

    /** 上跳一格：dest 上（y+1 脚 / y+2 头）可通行、dest 台阶面可站立、出发点地面可蹬踏且不攀爬 → 上跳代价。 */
    private static double ascendCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndPassable(destX, y + 2, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndStandable(destX, y, destZ)) {
            return MoveCosts.COST_INF;
        }
        // 出发点需站在实地上才能起跳蹬踏（不可为梯子/藤蔓等可攀爬面）
        if (!w.isLoadedAndStandable(x, y - 1, z) || w.isClimbable(x, y - 1, z)) {
            return MoveCosts.COST_INF;
        }
        return Math.max(MoveCosts.JUMP_ONE_BLOCK_COST, MoveCosts.WALK_ONE_BLOCK_COST);
    }

    /**
     * 斜向移动（对齐 Baritone {@code MovementDiagonal.cost}，去除门/水/霜行/岩浆分支）：
     * 根据 dest 列的地形自动判为上升 / 平地 / 下降三种落点：
     * <ul>
     *   <li>ASCEND：dest 同层脚部格不可通行（高一级的台阶）→ 需 src 头顶净空、dest(y) 可站、
     *       dest(y+2) 身体可通行、src 地面可蹬且不攀爬，落点 {@code y+1}，代价 √2×WALK + 上跳；</li>
     *   <li>FLAT：dest 同层脚部可通行且 dest(y-1) 可站→ 落点 {@code y}，代价 √2×WALK×表面乘子（可冲刺时再乘）；</li>
     *   <li>DESCEND：dest 同层可通行但 dest(y-1) 不可站、dest(y-2) 可站→ 落点 {@code y-1}，
     *       代价 √2×WALK + max(落1格, 落后居中)。</li>
     * </ul>
     * 三种情形均要求两条直角边脚部格（防穿角）畅通，且越掠的两个地面格不危险。
     */
    private static void diagonal(PathWorld w, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return; // 目标上半身不畅通
        }
        boolean ascend = false;
        boolean descend = false;
        if (!w.isLoadedAndPassable(destX, y, destZ)) {
            // dest 同层脚部被占 → 需上升一格
            ascend = true;
            if (!w.isLoadedAndPassable(x, y + 2, z) || !w.isLoadedAndPassable(destX, y + 2, destZ)) {
                return; // 起跳/落点头顶净空不足
            }
            if (!w.isLoadedAndStandable(destX, y, destZ)) {
                return; // 无可踩的台阶面
            }
        } else if (!w.isLoadedAndStandable(destX, y - 1, destZ)) {
            // dest 同层可通行但无地面 → 下降一格
            descend = true;
            if (!w.isLoadedAndStandable(destX, y - 2, destZ) || !w.isLoadedAndPassable(destX, y - 1, destZ)) {
                return;
            }
        }
        // 防穿角：两条直角边上的脚部格也须畅通
        if (!w.isLoadedAndPassable(destX, y, z) || !w.isLoadedAndPassable(x, y, destZ)) {
            return;
        }
        // 越掠经停的两个地面格不可危险（岩浆/火等；isStandable 已将危险面判不可站，
        // 但越掠面可能是非落脚的空气下危险块，需显式排除）
        if (w.isDangerousAt(destX, y - 1, z) || w.isDangerousAt(x, y - 1, destZ)) {
            return;
        }
        // 出发点需站在实地上（非可踩塌/攀爬）
        if (w.isClimbable(x, y - 1, z)) {
            return;
        }
        // 表面减速乘子：dest 落脚面与 src 地面各贡献一半（对齐 Baritone 的 /2 分配）
        double multiplier = MoveCosts.WALK_ONE_BLOCK_COST;
        double destSurface = ascend ? w.surfaceCostMultiplier(destX, y, destZ)
                : w.surfaceCostMultiplier(destX, y - 1, destZ);
        double fromSurface = w.surfaceCostMultiplier(x, y - 1, z);
        multiplier += MoveCosts.WALK_ONE_BLOCK_COST * (destSurface - 1.0) / 2.0;
        multiplier += MoveCosts.WALK_ONE_BLOCK_COST * (fromSurface - 1.0) / 2.0;
        res.x = destX;
        res.z = destZ;
        if (ascend) {
            res.y = y + 1;
            res.cost = multiplier * SQRT_2 + MoveCosts.JUMP_ONE_BLOCK_COST;
            return;
        }
        if (descend) {
            res.y = y - 1;
            res.cost = multiplier * SQRT_2
                    + Math.max(MoveCosts.FALL_N_BLOCKS_COST[1], MoveCosts.CENTER_AFTER_FALL_COST);
            return;
        }
        // 平地：不冲刺时可叠冲刺系数
        res.y = y;
        if (w.allowSprint()) {
            multiplier *= MoveCosts.SPRINT_MULTIPLIER;
        }
        res.cost = multiplier * SQRT_2;
    }

    /**
     * 对角破块穿越（{@code BREAK} 对角变体）：仅在 {@code canBreak()} 开启时，把 dest 同层
     * 脚/头格中不可通行但可挖掘的方块视为“挖开后即可走”。只支持平地对角（落点 {@code y}）：
     * dest 地面可站、两角脚部畅通且不危险，否则 {@link MoveCosts#COST_INF}。代价 = √2×WALK×
     * 表面乘子 + 各需挖方块 {@code breakCost} 之和。
     */
    private static void breakDiagonal(PathWorld w, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        if (!w.canBreak()) {
            return;
        }
        if (!w.isLoadedAndStandable(destX, y - 1, destZ)) {
            return;
        }
        // 防穿角：两条直角边脚部格须畅通，且越掠地面不危险
        if (!w.isLoadedAndPassable(destX, y, z) || !w.isLoadedAndPassable(x, y, destZ)) {
            return;
        }
        if (w.isDangerousAt(destX, y - 1, z) || w.isDangerousAt(x, y - 1, destZ)) {
            return;
        }
        double breakAdd = 0.0;
        if (!w.isLoadedAndPassable(destX, y, destZ)) {
            if (!w.isBreakable(destX, y, destZ)) {
                return;
            }
            double c = w.breakCost(destX, y, destZ);
            if (c >= MoveCosts.COST_INF) {
                return;
            }
            breakAdd += c;
        }
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            if (!w.isBreakable(destX, y + 1, destZ)) {
                return;
            }
            double c = w.breakCost(destX, y + 1, destZ);
            if (c >= MoveCosts.COST_INF) {
                return;
            }
            breakAdd += c;
        }
        double base = MoveCosts.WALK_ONE_BLOCK_COST * w.surfaceCostMultiplier(destX, y - 1, destZ) * SQRT_2;
        // 挖掘优先：挖块耗时打折，使直挖穿过的对角步胜过绕路
        if (w.digPriority()) {
            breakAdd *= MoveCosts.DIG_PRIORITY_BREAK_DISCOUNT;
        }
        res.x = destX;
        res.y = y;
        res.z = destZ;
        res.cost = base + breakAdd;
    }

    /**
     * Parkour 跳跨空隙（对齐 Baritone {@code MovementParkour.cost}，去除放置/梯子/水分支）：
     * 仅当相邻格为“脚部畅通且下方不可站”的空隙时才考虑跳跨（否则直接 traverse 即可）。
     * 从 i=2 到 maxJump（可冲刺 4，否则 3；灵魂沙起跳面降为 2）逐格扫描落点：
     <ul>
     *   <li>平落：dest(y-1) 可站且不危险、越冲安全 → 落点 {@code y}；</li>
     *   <li>上升落（仅冲刺且 i≤ 3）：dest(y) 不可通行但可站且不危险、越冲安全 → 落点 {@code y+1}；</li>
     * </ul>
     * 途中每一列的身体格（y+1/y+2）需畅通，空洞列 y+3 也需畅通才能继续向外扩展。代价由
     * {@link MoveCosts#parkourCost(int)} 按跨度给出。
     */
    private static void parkour(PathWorld w, int x, int y, int z, int dirX, int dirZ, MutableMoveResult res) {
        if (!w.allowParkour()) {
            return;
        }
        int adjX = x + dirX;
        int adjZ = z + dirZ;
        // 相邻格必须是可跨越的空隙（脚部畅通）且下方不可站（否则无需 parkour）
        if (!w.isLoadedAndPassable(adjX, y, adjZ) || w.isLoadedAndStandable(adjX, y - 1, adjZ)) {
            return;
        }
        if (w.isDangerousAt(adjX, y - 1, adjZ)) {
            return;
        }
        // 起跳需头顶两格净空，相邻格上方也需净空（不撞墙）
        if (!w.isLoadedAndPassable(x, y + 1, z) || !w.isLoadedAndPassable(x, y + 2, z)) {
            return;
        }
        if (!w.isLoadedAndPassable(adjX, y + 1, adjZ) || !w.isLoadedAndPassable(adjX, y + 2, adjZ)) {
            return;
        }
        // 起跳面需可蹬踏（实心、非攀爬）
        if (!w.isLoadedAndStandable(x, y - 1, z) || w.isClimbable(x, y - 1, z)) {
            return;
        }
        // 灵魂沙上起跳只能跨 1 格空隙（跨度 2）
        int maxJump = w.surfaceCostMultiplier(x, y - 1, z) > 1.0 ? 2 : (w.allowSprint() ? 4 : 3);
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + dirX * i;
            int destZ = z + dirZ * i;
            // 腾空经过此列，上半身（y+1/y+2）须畅通
            if (!w.isLoadedAndPassable(destX, y + 1, destZ) || !w.isLoadedAndPassable(destX, y + 2, destZ)) {
                return;
            }
            if (!w.isLoadedAndPassable(destX, y, destZ)) {
                // dest 同层被占：可能为上升落点（跳上高一级）；仅冲刺且 i≤ 3
                if (i <= 3 && w.allowSprint() && w.isLoadedAndStandable(destX, y, destZ)
                        && !w.isDangerousAt(destX, y, destZ)
                        && overshootSafe(w, destX + dirX, y + 1, destZ + dirZ)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = MoveCosts.parkourCost(i) + MoveCosts.JUMP_ONE_BLOCK_COST;
                }
                return; // 撞墙/障碍，无法继续向外
            }
            // 平地落点：dest 下方(y-1) 可站
            if (w.isLoadedAndStandable(destX, y - 1, destZ)) {
                if (!w.isDangerousAt(destX, y - 1, destZ)
                        && overshootSafe(w, destX + dirX, y, destZ + dirZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = MoveCosts.parkourCost(i);
                }
                return;
            }
            // 仍为空洞：要继续向外扫描，此列更上方(y+3) 也须畅通
            if (!w.isLoadedAndPassable(destX, y + 3, destZ)) {
                return;
            }
        }
    }

    /** 落点越冲安全：落点再往前一格的脚/头两格不危险（避免冲过头撞入岩浆等）。 */
    private static boolean overshootSafe(PathWorld w, int ox, int oy, int oz) {
        return !w.isDangerousAt(ox, oy, oz) && !w.isDangerousAt(ox, oy + 1, oz);
    }

    /**
     * 原地向上搭建（对齐 Baritone {@code MovementPillar}）：
     * 从当前位置向上移动一格，需要放置方块垫脚或利用梯子/藤蔓攀爬。
     *
     * <p>条件：</p>
     * <ul>
     *   <li>头顶两格（y+1, y+2）必须畅通；</li>
     *   <li>若脚下是可攀爬方块（梯子/藤蔓），则直接向上爬；</li>
     *   <li>否则需要放置方块垫脚（需 canPlace 开启且有可用方块）；</li>
     *   <li>脚下不能是岩浆块等危险方块（除非允许）。</li>
     * </ul>
     */
    private static void pillar(PathWorld w, int x, int y, int z, MutableMoveResult res) {
        // 头顶两格必须畅通
        if (!w.isLoadedAndPassable(x, y + 1, z) || !w.isLoadedAndPassable(x, y + 2, z)) {
            return;
        }
        // 脚下不能是危险方块
        if (w.isDangerousAt(x, y - 1, z)) {
            return;
        }
        // 若脚下是可攀爬方块（梯子/藤蔓），则直接向上爬
        if (w.isClimbable(x, y - 1, z)) {
            res.x = x;
            res.y = y + 1;
            res.z = z;
            res.cost = MoveCosts.LADDER_UP_ONE_COST;
            return;
        }
        // 否则需要放置方块垫脚
        if (!w.canPlace()) {
            return;
        }
        // 脚下必须可站立（不能是空气）
        if (!w.isLoadedAndStandable(x, y - 1, z)) {
            return;
        }
        // 放置代价 + 跳跃代价
        res.x = x;
        res.y = y + 1;
        res.z = z;
        res.cost = MoveCosts.JUMP_ONE_BLOCK_COST + MoveCosts.PLACE_ONE_BLOCK_COST;
    }

    /**
     * 放置搭桥（对齐 Baritone {@code MovementTraverse} 的 bridge 分支）：当 dest 脚下无站立面时，
     * 用背包方块在 dest 脚下铺一块地板再走上去。前提：{@code canPlace()} 开启、dest 脚+头可通行、
     * dest 脚下“可替换（空气/水/岩浆/草等）”或“不可替换但可挖”、存在放置锚点（dest 正下方 /
     * 四横向相邻 / 起点脚下忍者桥）、出发点脚下可站立。不可替换的可挖方块先挖掉再放，挖块耗时
     * 计入代价。代价 = 走一格 + {@link MoveCosts#PLACE_ONE_BLOCK_COST}（+ 挖块 tick）。
     *
     * <p>落点与 TRAVERSE 相同（{@code destX, y, destZ}），仅在 dest 脚下不可站时才有非
     * {@link MoveCosts#COST_INF} 代价（此时 TRAVERSE 返回 COST_INF），两者互斥不产生重复节点。</p>
     */
    private static double bridgeCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        if (!w.canPlace()) {
            return MoveCosts.COST_INF;
        }
        // dest 脚 + 头可通行（走过去不撞墙）
        if (!w.isLoadedAndPassable(destX, y, destZ) || !w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        // dest 脚下必须不可站（缺口）——否则普通 TRAVERSE 已覆盖，无需搭桥
        if (w.isLoadedAndStandable(destX, y - 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        // 目标地板格分类：可替换（空气/水/岩浆/草等）→ 直接放；不可替换但可挖 → 挖掉再放；否则不可达
        double extraCost = 0.0;
        if (!w.isReplaceable(destX, y - 1, destZ)) {
            if (!w.isBreakable(destX, y - 1, destZ)) {
                return MoveCosts.COST_INF;
            }
            double c = w.breakCost(destX, y - 1, destZ);
            if (c >= MoveCosts.COST_INF) {
                return MoveCosts.COST_INF;
            }
            extraCost = c;
        }
        // 出发点脚下可站立（站得住才能放方块）
        if (!w.isLoadedAndStandable(x, y - 1, z)) {
            return MoveCosts.COST_INF;
        }
        // 存在放置锚点（与 PathingGoal#findPlaceAnchor 同一套三级锚点）
        if (!hasPlaceAnchor(w, x, y, z, destX, destZ)) {
            return MoveCosts.COST_INF;
        }
        return MoveCosts.WALK_ONE_BLOCK_COST + MoveCosts.PLACE_ONE_BLOCK_COST + extraCost;
    }

    /** 目标地板格 {@code (destX, y-1, destZ)} 是否存在可依附的放置锚点（UP 面 / 侧向 / 忍者桥）。 */
    private static boolean hasPlaceAnchor(PathWorld w, int x, int y, int z, int destX, int destZ) {
        // ① 目标正下方实心块 → 点其顶面（UP）
        if (w.isLoadedAndStandable(destX, y - 2, destZ)) {
            return true;
        }
        // ② 目标地板格四横向相邻实心块 → 点其侧面（跳过站立列，留作忍者桥）
        int[][] offs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offs) {
            int ax = destX + o[0];
            int az = destZ + o[1];
            if (ax == x && az == z) {
                continue;
            }
            if (w.isLoadedAndStandable(ax, y - 1, az)) {
                return true;
            }
        }
        // ③ 忍者桥：起点脚下实心块 → 点其侧面（潜行进边后可见）
        return w.isLoadedAndStandable(x, y - 1, z);
    }

    /**
     * 水中平游一格（对齐 Baritone {@code MovementTraverse} 的水分支）：dest 脚+头均为水，
     * 且 dest 下方不是岩浆（防烫伤）。代价 = 走一格 × 水中减速（约 2 倍，对齐原版 0.8 水平衰减）。
     *
     * <p>若当前位置不在水中（如岸边），入水代价额外 ×3（避免非必要入水）；
     * 若已在水中，则正常游泳代价（鼓励水中继续游泳而非上岸）。</p>
     */
    private static double swimCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        // dest 脚 + 头均为水（可游泳通过）
        if (!w.isWater(destX, y, destZ) || !w.isWater(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        // dest 下方不是岩浆（防烫伤；水下方块可以是任意可站立面或空气）
        if (w.isLava(destX, y - 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        // 水中水平移动代价：走一格 × 2（对齐原版 0.8 水平衰减的倒数 1/0.8=1.25，取保守 2.0 覆盖加速与转向）
        double cost = MoveCosts.WALK_ONE_BLOCK_COST * 2.0;
        // 当前位置不在水中（如岸边）→ 入水代价额外 ×3（避免非必要入水）
        if (!w.isWater(x, y, z)) {
            cost *= 3.0;
        }
        return cost;
    }

    /**
     * 水中向上浮一格（对齐原版 jumpInLiquid 上浮 0.04/tick）：dest 为水、头顶为水、
     * 当前位置为水。代价 = 走一格 × 2.5（上浮比平游慢）。
     */
    private static double swimUpCost(PathWorld w, int x, int y, int z) {
        if (!w.isWater(x, y, z) || !w.isWater(x, y + 1, z)) {
            return MoveCosts.COST_INF;
        }
        return MoveCosts.WALK_ONE_BLOCK_COST * 2.5;
    }

    /**
     * 水中向下沉一格（对齐原版 goDownInWater 下沉 0.04/tick）：dest 为水、当前位置为水。
     * 代价 = 走一格 × 1.5（下沉比平游快，借助重力）。
     */
    private static double swimDownCost(PathWorld w, int x, int y, int z) {
        if (!w.isWater(x, y, z) || !w.isWater(x, y - 1, z)) {
            return MoveCosts.COST_INF;
        }
        return MoveCosts.WALK_ONE_BLOCK_COST * 1.5;
    }

    /**
     * 水中向岸边移动一格：dest 是可站立的岸边（非水），当前位置在水中。
     * 代价 = 走一格 × 2.5（出水阻力 + 上岸动作）。
     */
    private static double swimToShoreCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        // 当前位置须在水中
        if (!w.isWater(x, y, z)) {
            return MoveCosts.COST_INF;
        }
        // dest 必须可站立（岸边）且非水
        if (!w.isLoadedAndStandable(destX, y - 1, destZ) || w.isWater(destX, y, destZ)) {
            return MoveCosts.COST_INF;
        }
        // dest 脚+头可通行（走上岸不撞墙）
        if (!w.isLoadedAndPassable(destX, y, destZ) || !w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        return MoveCosts.WALK_ONE_BLOCK_COST * 2.5;
    }

    /**
     * 破块横向穿越：在 {@code canBreak()} 开启时，把目标列脚/头格中不可通行但可挖掘的方块
     * 视为“挖开后即可走”。代价 = 基础走格（含冲刺系数）+ 各需挖方块 {@code breakCost} 之和。
     * 脚下不可站、或脚/头任一格既不可通行又不可挖（如基岩/流体/未加载）→ {@link MoveCosts#COST_INF}。
     * 破块所需 tick 计入 A* 代价，使寻路自然偏向“绕开硬块 / 选最快可挖通道”。
     */
    private static double breakTraverseCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        if (!w.canBreak()) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndStandable(destX, y - 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        double breakAdd = 0.0;
        // 脚部格：不可通行则必须可挖，计入破块代价
        if (!w.isLoadedAndPassable(destX, y, destZ)) {
            if (!w.isBreakable(destX, y, destZ)) {
                return MoveCosts.COST_INF;
            }
            double c = w.breakCost(destX, y, destZ);
            if (c >= MoveCosts.COST_INF) {
                return MoveCosts.COST_INF;
            }
            breakAdd += c;
        }
        // 头部格：同理
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            if (!w.isBreakable(destX, y + 1, destZ)) {
                return MoveCosts.COST_INF;
            }
            double c = w.breakCost(destX, y + 1, destZ);
            if (c >= MoveCosts.COST_INF) {
                return MoveCosts.COST_INF;
            }
            breakAdd += c;
        }
        double base = MoveCosts.WALK_ONE_BLOCK_COST;
        if (w.allowSprint()) {
            base *= MoveCosts.SPRINT_MULTIPLIER;
        }
        // 挖掘优先：挖块耗时打折，使 A* 选择“直挖前进”而非绕路（移动优先则按真实耗时计）
        if (w.digPriority()) {
            breakAdd *= MoveCosts.DIG_PRIORITY_BREAK_DISCOUNT;
        }
        return base + breakAdd;
    }

    /**
     * 动态下落 / 落地（合并 Baritone 的 Descend 与 Fall）：在目标列 (destX,destZ) 从
     * {@code y-1} 向下找到第一个"脚部可站于其上"的落脚点，其间脚部须畅通、不超过
     * {@link PathWorld#maxFallHeight()}，代价按 WALK_OFF + 落 N 格。写入 {@code res}；
     * 不可达时保持 {@link MoveCosts#COST_INF}。
     */
    private static void fall(PathWorld w, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        int minY = w.minY();
        int maxFall = w.maxFallHeight();
        for (int cand = y - 1; cand >= minY; cand--) {
            int drop = y - cand;
            if (drop > maxFall) {
                return; // 超出可承受坠落高度
            }
            // 下坠过程经过的脚部与头部须畅通
            if (!w.isLoadedAndPassable(destX, cand, destZ) || !w.isLoadedAndPassable(destX, cand + 1, destZ)) {
                return;
            }
            if (w.isLoadedAndStandable(destX, cand - 1, destZ)) {
                int idx = Math.min(drop, MoveCosts.FALL_N_BLOCKS_COST.length - 1);
                res.x = destX;
                res.y = cand;
                res.z = destZ;
                res.cost = MoveCosts.WALK_OFF_BLOCK_COST + MoveCosts.FALL_N_BLOCKS_COST[idx];
                return;
            }
        }
        // 落到世界底部仍未找到落脚点：不可达（可能是虚空）
    }

    /**
     * 鞘翅滑翔边（本项目自建，对齐弹道表模型）：从 src 平台起跳离台后沿
     * {@code (dirX, dirZ)} 方向按 {@link GlideTable} 采样弹道（脚点 = 起点脚高 − 累计下落，
     * 水平距离每 0.5 格一采样）逐段前推：
     * <ul>
     *   <li>掠过的脚格必须可通行（滑翔碰撞盒高仅 0.6，保守按脚格判撞）；</li>
     *   <li>首次踩到实心列时：若该顶面可站、其上两格畅通且不低于起点 → 着陆成功，
     *       dest 为落脚点；否则判撞壁（不可行）；</li>
     *   <li>离开已加载区/超出 {@code glideMaxDistance}/火箭超预算 → 截断为不可行
     *       （跨区块续算由分段机制处理）；</li>
     *   <li>水平跨度 &lt; 4 格不生成（交给 DESCEND/PARKOUR）。</li>
     * </ul>
     * 代价 = 弹道 tick 数 + 火箭数 × {@link MoveCosts#ROCKET_ONE_COST}。着陆免伤性由
     * 表剖面保证（{@code profileSafe}：全程垂直速度浅于 −0.5，官方摔落距离封顶 1.0）。
     */
    private static void glide(PathWorld w, int x, int y, int z, int dirX, int dirZ, MutableMoveResult res) {
        GlideTable table = w.glideTable();
        if (!w.allowGlide() || table == null) {
            return;
        }
        // src 必须站在实心平台上且头顶净空（能起跳离台）
        if (!w.isLoadedAndStandable(x, y - 1, z) || !w.isLoadedAndPassable(x, y + 1, z)) {
            return;
        }
        // 出射方向紧邻一格需畅通（跳离台面后不能立即撞壁）
        int adjX = x + dirX;
        int adjZ = z + dirZ;
        if (!w.isLoadedAndPassable(adjX, y, adjZ) || !w.isLoadedAndPassable(adjX, y + 1, adjZ)) {
            return;
        }
        double maxDist = Math.min(w.glideMaxDistance(), table.totalHoriz());
        for (double d = 0.5; d <= maxDist; d += 0.5) {
            double[] sample = table.sampleAtHoriz(d);
            if (sample == null) {
                return; // 弹道表耗尽
            }
            double footY = y - sample[1];
            if (footY < w.minY()) {
                return; // 落到世界底部以下（虚空）
            }
            int cx = floorCell(x + 0.5 + dirX * d);
            int cz = floorCell(z + 0.5 + dirZ * d);
            if (!w.isLoaded(cx, cz)) {
                return; // 越出已加载区：保守截断，分段续算后自然衔接
            }
            int fy = floorCell(footY);
            if (fy > y) {
                return; // 弹道抬升到高于起点平台（仅大抬头角可能）：不考虑回踩上平台
            }
            if (w.isPassable(cx, fy, cz)) {
                // 水不可作落点也不阻断掠过（保守：掠水继续往前找实心面）；
                // 岩浆已在 isPassable 内判不可通行，撞上即失败
                continue;
            }
            // 踩到实心列：必须作为平台顶面可站、其上两格可站人，否则判撞壁
            if (!w.isStandable(cx, fy, cz)
                    || !w.isLoadedAndPassable(cx, fy + 1, cz) || !w.isLoadedAndPassable(cx, fy + 2, cz)) {
                return;
            }
            int destY = fy + 1;
            int rockets = (int) sample[3];
            if (rockets > w.glideRocketBudget()) {
                return; // 补给超出预算
            }
            double span = Math.hypot(cx - x, cz - z);
            if (span < 4.0) {
                return; // 跨度过小不值得滑翔
            }
            res.x = cx;
            res.y = destY;
            res.z = cz;
            res.cost = sample[4] + rockets * MoveCosts.ROCKET_ONE_COST;
            return;
        }
        // 在最大跨度内未找到落脚点：不可行（继续飞行越界后交给分段）
    }

    /** 地板取整（与物理层 {@code MathHelper.floor} 同义）。 */
    private static int floorCell(double value) {
        return (int) Math.floor(value);
    }
}
