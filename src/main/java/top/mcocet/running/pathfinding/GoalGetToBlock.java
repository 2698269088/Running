/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.api.pathing.goals.GoalGetToBlock。
 */
package top.mcocet.running.pathfinding;

/**
 * 不进入目标方块，而是到达其直接相邻位置（例如开箱子的场景）。移植自 Baritone（avecowa）。
 */
public class GoalGetToBlock implements Goal {

    public final int x;
    public final int y;
    public final int z;

    public GoalGetToBlock(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public GoalGetToBlock(NodePos pos) {
        this(pos.x, pos.y, pos.z);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return Math.abs(xDiff) + Math.abs(yDiff < 0 ? yDiff + 1 : yDiff) + Math.abs(zDiff) <= 1;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int xDiff = x - this.x;
        int yDiff = y - this.y;
        int zDiff = z - this.z;
        return GoalBlock.calculate(xDiff, yDiff < 0 ? yDiff + 1 : yDiff, zDiff);
    }

    public NodePos getGoalPos() {
        return new NodePos(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GoalGetToBlock)) {
            return false;
        }
        GoalGetToBlock goal = (GoalGetToBlock) o;
        return x == goal.x && y == goal.y && z == goal.z;
    }

    @Override
    public int hashCode() {
        return (int) NodePos.longHash(x, y, z) * -49639096;
    }

    @Override
    public String toString() {
        return "GoalGetToBlock{x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
