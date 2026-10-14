/*
 * 本文件的结构与代价模型移植自 Baritone（LGPL-3.0）的
 * baritone.pathing.movement.Moves 及 movements/MovementTraverse|Ascend|Descend|Downward。
 * 首版仅保留基础移动，删去破块 / 放置 / 梯子 / 水 / 霜行 / parkour 等分支，
 * 并把 MC 的 {@code canWalkThrough}/{@code canWalkOn} 替换为 {@link PathWorld} 的
 * {@link PathWorld#isPassable}/{@link PathWorld#isStandable}。
 */
package top.mcocet.running.pathfinding;

/**
 * 基础移动枚举子集。每个 move 携带名义偏移（供 A* 做区块加载 / 边界判定）与
 * {@link #apply(PathWorld, int, int, int, MutableMoveResult)}：在 {@link PathWorld} 上计算
 * 落点与代价写入结果对象。
 *
 * <p>{@code dynamicY=true} 的 move（{@code DOWNWARD}/{@code DESCEND_*}）落点 Y 依地形动态确定
 * （可能是落 1 格，也可能是多格坠落）；其余 move 落点为名义偏移。{@code dynamicXZ} 恒为
 * false（XZ 偏移固定），与 Baritone 对这些基础动作的定义一致。</p>
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

    DIAGONAL_NORTHEAST(1, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return diagonalCost(w, x, y, z, x + 1, z - 1);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return diagonalCost(w, x, y, z, x - 1, z - 1);
        }
    },

    DIAGONAL_SOUTHEAST(1, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return diagonalCost(w, x, y, z, x + 1, z + 1);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, 1) {
        @Override
        public double cost(PathWorld w, int x, int y, int z) {
            return diagonalCost(w, x, y, z, x - 1, z + 1);
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
        if (w.allowSprint()) {
            cost *= MoveCosts.SPRINT_MULTIPLIER;
        }
        return cost;
    }

    /** 上跳一格：dest 上（y+1 脚 / y+2 头）可通行、dest 台阶面可站立、出发点地面可蹬踏 → 上跳代价。 */
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
        // 出发点需站在实地上才能起跳蹬踏
        if (!w.isLoadedAndStandable(x, y - 1, z)) {
            return MoveCosts.COST_INF;
        }
        return Math.max(MoveCosts.JUMP_ONE_BLOCK_COST, MoveCosts.WALK_ONE_BLOCK_COST);
    }

    /** 斜向走一格：dest 脚+头可通行、脚下可站、且两侧相邻格脚部畅通（防穿角）→ √2 × WALK。 */
    private static double diagonalCost(PathWorld w, int x, int y, int z, int destX, int destZ) {
        if (!w.isLoadedAndPassable(destX, y, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndPassable(destX, y + 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndStandable(destX, y - 1, destZ)) {
            return MoveCosts.COST_INF;
        }
        // 防穿角：两条直角边上的脚部格子也须畅通
        if (!w.isLoadedAndPassable(destX, y, z)) {
            return MoveCosts.COST_INF;
        }
        if (!w.isLoadedAndPassable(x, y, destZ)) {
            return MoveCosts.COST_INF;
        }
        return MoveCosts.WALK_ONE_BLOCK_COST * SQRT_2;
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
}
