/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalBlock。
 */
package top.mcocet.running.pathfinding;

/**
 * 指定到一个确切方块坐标的目标。移植自 Baritone（leijurv）。
 */
public class GoalBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public GoalBlock(NodePos pos) {
        this(pos.x, pos.y, pos.z);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalBlock)) {
            return false;
        }
        GoalBlock goal = (GoalBlock) o;
        return x == goal.x && y == goal.y && z == goal.z;
    }

    @Override
    public int hashCode() {
        return (int) NodePos.longHash(x, y, z) * 905165533;
    }

    @Override
    public String toString() {
        return "GoalBlock{x=" + x + ",y=" + y + ",z=" + z + "}";
    }

    /** Y 分量按 {@link GoalYLevel}，XZ 分量按 {@link GoalXZ} 的混合度量。 */
    public static double calculate(double xDiff, int yDiff, double zDiff) {
        double heuristic = 0;
        heuristic += GoalYLevel.calculate(0, yDiff);
        heuristic += GoalXZ.calculate(xDiff, zDiff);
        return heuristic;
    }
}
