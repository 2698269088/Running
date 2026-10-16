/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalNear。
 */
package top.mcocet.running.pathfinding;

/**
 * 到达目标点周围 {@code range} 格球形范围内。移植自 Baritone，去掉 fastutil 依赖，
 * {@link #heuristic()} 的"保证落在范围内"下界用普通循环与数组实现。
 */
public class GoalNear implements Goal {

    private final int x;
    private final int y;
    private final int z;
    private final int rangeSq;

    public GoalNear(int x, int y, int z, int range) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.rangeSq = range * range;
    }

    public GoalNear(NodePos pos, int range) {
        this(pos.x, pos.y, pos.z, range);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= rangeSq;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return GoalBlock.calculate(x - this.x, y - this.y, z - this.z);
    }

    @Override
    public double heuristic() {
        int range = (int) Math.ceil(Math.sqrt(rangeSq));
        // 收集"在范围内"的启发式候选，取其中小于范围外最小值者的最大者，
        // 作为目标已满足时的启发式下界（移植 Baritone 的 less-hacky 逻辑，去掉 fastutil）
        double[] inside = new double[(2 * range + 1) * (2 * range + 1) * (2 * range + 1)];
        int count = 0;
        double minOutside = Double.POSITIVE_INFINITY;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    double h = heuristic(x + dx, y + dy, z + dz);
                    if (h < minOutside && isInGoal(x + dx, y + dy, z + dz)) {
                        inside[count++] = h;
                    } else {
                        minOutside = Math.min(minOutside, h);
                    }
                }
            }
        }
        double maxInside = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            if (inside[i] < minOutside) {
                maxInside = Math.max(maxInside, inside[i]);
            }
        }
        return maxInside;
    }

    public NodePos getGoalPos() {
        return new NodePos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalNear)) {
            return false;
        }
        GoalNear goal = (GoalNear) o;
        return x == goal.x && y == goal.y && z == goal.z && rangeSq == goal.rangeSq;
    }

    @Override
    public int hashCode() {
        return (int) NodePos.longHash(x, y, z) + rangeSq;
    }

    @Override
    public String toString() {
        return "GoalNear{x=" + x + ",y=" + y + ",z=" + z + ",rangeSq=" + rangeSq + "}";
    }
}
