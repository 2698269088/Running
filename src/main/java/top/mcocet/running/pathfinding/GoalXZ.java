/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalXZ。
 */
package top.mcocet.running.pathfinding;

/**
 * 只有 XZ、不指定 Y 的远距离目标。移植自 Baritone（leijurv），去掉 {@code BaritoneAPI.getSettings()}
 * 与 MC {@code Vec3}/{@code Mth} 依赖，启发式乘子改用 {@link MoveCosts#COST_HEURISTIC} 常量。
 */
public class GoalXZ implements Goal {

    private static final double SQRT_2 = Math.sqrt(2);

    private final int x;
    private final int z;

    public GoalXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public GoalXZ(NodePos pos) {
        this(pos.x, pos.z);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, z - this.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalXZ)) {
            return false;
        }
        GoalXZ goal = (GoalXZ) o;
        return x == goal.x && z == goal.z;
    }

    @Override
    public int hashCode() {
        int hash = 1791873246;
        hash = hash * 222601791 + x;
        hash = hash * -1331679453 + z;
        return hash;
    }

    @Override
    public String toString() {
        return "GoalXZ{x=" + x + ",z=" + z + "}";
    }

    /**
     * 勾股与曼哈顿的混合：同时估计"斜走"和"直走"的耗时（斜走每格 √2），
     * 保证启发式可采纳。移植自 Baritone。
     */
    public static double calculate(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        diagonal *= SQRT_2;
        return (diagonal + straight) * MoveCosts.COST_HEURISTIC;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }
}
