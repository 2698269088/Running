/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.pathing.calc.PathNode。
 */
package top.mcocet.running.pathfinding;

/**
 * 搜索图中的一节点：位置、到起点的实际代价、到起点的启发式合并代价，以及回溯用的前驱。
 * 移植自 Baritone（leijurv），把 {@code BetterBlockPos}/{@code SettingsUtil} 依赖替换为本包的
 * {@link NodePos} 与原生格式化。
 */
public final class PathNode {

    /** 节点方块坐标 */
    public final int x;
    public final int y;
    public final int z;

    /** 缓存的启发式，恒等于 {@code goal.heuristic(x,y,z)} */
    public final double estimatedCostToGoal;

    /** 从起点到本节点的累计代价（可变，由搜索更新） */
    public double cost;

    /** 恒等于 {@code estimatedCostToGoal + cost}（可变，由搜索更新） */
    public double combinedCost;

    /** 图中贡献了本节点最优代价的前驱（可变，由搜索更新） */
    public PathNode previous;

    /** 到达本节点所使用的移动（与 {@link #previous} 配对；由搜索设置，供 {@link Path} 重建） */
    public Moves move;

    /** 本节点在二叉堆中的下标；-1 表示不在 open set 中 */
    public int heapPosition;

    public PathNode(int x, int y, int z, Goal goal) {
        this.previous = null;
        this.cost = MoveCosts.COST_INF;
        this.estimatedCostToGoal = goal.heuristic(x, y, z);
        if (Double.isNaN(this.estimatedCostToGoal)) {
            throw new IllegalStateException(
                    goal + " 在 (" + x + "," + y + "," + z + ") 计算出非法的 NaN 启发式");
        }
        this.heapPosition = -1;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** 是否位于 open set（二叉堆）中 */
    public boolean isOpen() {
        return heapPosition != -1;
    }

    @Override
    public int hashCode() {
        return (int) NodePos.longHash(x, y, z);
    }

    @Override
    public boolean equals(Object obj) {
        // 热点路径：仅比较坐标（与 Baritone 一致）
        final PathNode other = (PathNode) obj;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public String toString() {
        return "PathNode{x=" + x + ",y=" + y + ",z=" + z + ",cost=" + cost + "}";
    }
}
