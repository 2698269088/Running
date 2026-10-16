/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalYLevel。
 */
package top.mcocet.running.pathfinding;

/**
 * 仅关注目标 Y 层级的目标（XZ 任意）。移植自 Baritone，去掉 MC 依赖。
 */
public class GoalYLevel implements Goal {

    public final int level;

    public GoalYLevel(int level) {
        this.level = level;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return y == level;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return calculate(level, y);
    }

    /** 从 currentY 到 goalY 的估计代价：下降按坠落、上升按跳格。 */
    public static double calculate(int goalY, int currentY) {
        if (currentY > goalY) {
            // 需要下降
            return MoveCosts.FALL_N_BLOCKS_COST[2] / 2 * (currentY - goalY);
        }
        if (currentY < goalY) {
            // 需要上升
            return (currentY < goalY ? goalY - currentY : 0) * MoveCosts.JUMP_ONE_BLOCK_COST;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalYLevel)) {
            return false;
        }
        return level == ((GoalYLevel) o).level;
    }

    @Override
    public int hashCode() {
        return level * 1271009915;
    }

    @Override
    public String toString() {
        return "GoalYLevel{y=" + level + "}";
    }
}
