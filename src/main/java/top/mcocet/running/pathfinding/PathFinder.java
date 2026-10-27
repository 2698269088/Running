/*
 * 本文件的主循环移植自 Baritone（LGPL-3.0）的
 * baritone.pathing.calc.AStarPathFinder#calculate0 与 baritone.pathing.calc.AbstractNodeCostSearch。
 * 去掉了 Favoring、世界边界、slowPath、异步分段与 fastutil 依赖，节点分桶保留。
 */
package top.mcocet.running.pathfinding;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A* 寻路主引擎，忠实移植 Baritone 的搜索循环：
 * <ul>
 *   <li>二叉堆 open set（{@link BinaryHeapOpenSet}）按 {@code combinedCost} 取最小；</li>
 *   <li>节点按 16×8×16 的 cell 分桶存放，缓存友好（{@link #getNodeAtPosition}）；</li>
 *   <li>增量代价回退（incremental cost backoff）：用 {@code COEFFICIENTS} 族记录各权重下的
 *       最优节点 {@link #bestSoFar}，超时 / 无解时回退到"至少推进 5 格"
 *       的最好分段；</li>
 *   <li>{@code MIN_IMPROVEMENT}：小于百分之一 tick 的浮点改善不重传播；</li>
 *   <li>搜索只在已加载区块内推进，越界即保守截断（{@link PathWorld} 谓词保证）。</li>
 * </ul>
 *
 * <p>本类非线程安全：一次 {@link #findPath} 应在单一计算线程调用；{@link #cancel()} 可跨线程
 * 设置中止标志。首版为同步计算。</p>
 */
public final class PathFinder {

    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    private static final double[] INVERSE_COEFFICIENTS = new double[COEFFICIENTS.length];

    static {
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            INVERSE_COEFFICIENTS[i] = 1 / COEFFICIENTS[i];
        }
    }

    /** 少于该格数且未到目标的路径不值得采纳（分段下限） */
    private static final double MIN_DIST_PATH = 5;
    /** 小于此值（tick）的代价改善视为浮点噪声，不触发更新 */
    private static final double MIN_IMPROVEMENT = 0.01;
    /** 连续探到未加载区块达到该次数即停止扩展（区块边界） */
    private static final int MAX_EMPTY_CHUNK = 256;

    private static final int CELL_BITS_X = 4;
    private static final int CELL_BITS_Y = 3;
    private static final int CELL_BITS_Z = 4;
    private static final int CELL_SIZE = 1 << (CELL_BITS_X + CELL_BITS_Y + CELL_BITS_Z);

    private final PathWorld world;
    private final Goal goal;
    private final int startX;
    private final int startY;
    private final int startZ;
    /** 回溯惩罚与避让区域（可为空） */
    private final Favoring favoring;

    private final Map<Long, PathNode[]> cells = new HashMap<>();
    private PathNode[] prevCell;
    private long prevCellKey = Long.MIN_VALUE;

    private PathNode startNode;
    private final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];

    private volatile boolean cancelRequested;

    public PathFinder(PathWorld world, Goal goal, int startX, int startY, int startZ) {
        this(world, goal, startX, startY, startZ, new Favoring());
    }

    public PathFinder(PathWorld world, Goal goal, int startX, int startY, int startZ, Favoring favoring) {
        this.world = world;
        this.goal = goal;
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.favoring = favoring != null ? favoring : new Favoring();
    }

    /** 请求中止当前计算（跨线程安全；下次 findPath 会重置该标志） */
    public void cancel() {
        cancelRequested = true;
    }

    /** 计算结果类型 */
    public enum Type {
        /** 成功到达目标 */
        SUCCESS_TO_GOAL,
        /** 未达目标，但找到推进了一段的可执行分段 */
        SUCCESS_SEGMENT,
        /** 无任何可行路径 */
        FAILURE,
        /** 被取消 */
        CANCELLATION
    }

    /** 计算结果：类型 + 可行时的路径 */
    public static final class Result {
        public final Type type;
        public final Path path;

        Result(Type type, Path path) {
            this.type = type;
            this.path = path;
        }

        public boolean isSuccess() {
            return path != null;
        }
    }

    /**
     * 同步计算从起点到目标的路径。
     *
     * @param primaryTimeoutMs 找到可行路径后允许继续优化的最长时间
     * @param failureTimeoutMs 尚未找到任何可行分段时允许搜索的最长时间
     */
    public Result findPath(long primaryTimeoutMs, long failureTimeoutMs) {
        cancelRequested = false;
        Optional<PathNode> reached = calculate(primaryTimeoutMs, failureTimeoutMs);
        if (cancelRequested) {
            return new Result(Type.CANCELLATION, null);
        }
        if (reached.isEmpty()) {
            return new Result(Type.FAILURE, null);
        }
        PathNode end = reached.get();
        Path path = Path.fromNodeChain(end);
        if (goal.isInGoal(end.x, end.y, end.z)) {
            return new Result(Type.SUCCESS_TO_GOAL, path);
        }
        return new Result(Type.SUCCESS_SEGMENT, path);
    }

    /**
     * A* 主循环。返回到达目标的终点节点，或（无解时）{@link #bestSoFar} 回退选出的最好分段终点。
     */
    private Optional<PathNode> calculate(long primaryTimeoutMs, long failureTimeoutMs) {
        int minY = world.minY();
        int maxY = world.maxY();

        startNode = getNodeAtPosition(startX, startY, startZ, NodePos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        startNode.previous = null;
        startNode.move = null;

        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);

        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }

        MutableMoveResult res = new MutableMoveResult();
        long startTime = System.currentTimeMillis();
        long primaryTimeoutAt = startTime + primaryTimeoutMs;
        long failureTimeoutAt = startTime + failureTimeoutMs;
        boolean failing = true;
        int numNodes = 0;
        int numEmptyChunk = 0;
        int timeCheckMask = (1 << 6) - 1; // 每 64 节点检查一次时间
        Moves[] allMoves = Moves.values();

        while (!openSet.isEmpty() && numEmptyChunk < MAX_EMPTY_CHUNK && !cancelRequested) {
            if ((numNodes & timeCheckMask) == 0) {
                long now = System.currentTimeMillis();
                if (now - failureTimeoutAt >= 0 || (!failing && now - primaryTimeoutAt >= 0)) {
                    break;
                }
            }
            PathNode currentNode = openSet.removeLowest();
            numNodes++;
            if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                return Optional.of(currentNode);
            }
            for (Moves moves : allMoves) {
                int newX = currentNode.x + moves.xOffset;
                int newZ = currentNode.z + moves.zOffset;
                // 跨区块时先确认目标区块已加载（否则计为边界截断）
                if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !world.isLoaded(newX, newZ)) {
                    if (!moves.dynamicXZ) {
                        numEmptyChunk++;
                    }
                    continue;
                }
                int nominalY = currentNode.y + moves.yOffset;
                if (nominalY > maxY || nominalY < minY) {
                    continue;
                }
                res.reset();
                moves.apply(world, currentNode.x, currentNode.y, currentNode.z, res);
                double actionCost = res.cost;
                if (actionCost >= MoveCosts.COST_INF) {
                    continue;
                }
                if (actionCost <= 0 || Double.isNaN(actionCost)) {
                    throw new IllegalStateException(
                            moves + " 从 (" + currentNode.x + "," + currentNode.y + "," + currentNode.z
                                    + ") 计算出非法代价 " + actionCost);
                }
                // 回溯惩罚：若目标位置在 favoring 表中，则乘以惩罚系数
                if (!favoring.isEmpty()) {
                    actionCost *= favoring.calculate(res.x, res.y, res.z);
                }
                long hashCode = NodePos.longHash(res.x, res.y, res.z);
                PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                double tentativeCost = currentNode.cost + actionCost;
                if (neighbor.cost - tentativeCost > MIN_IMPROVEMENT) {
                    neighbor.previous = currentNode;
                    neighbor.move = moves;
                    neighbor.cost = tentativeCost;
                    neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                    if (neighbor.isOpen()) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double heuristic = neighbor.estimatedCostToGoal + neighbor.cost * INVERSE_COEFFICIENTS[i];
                        if (bestHeuristicSoFar[i] - heuristic > MIN_IMPROVEMENT) {
                            bestHeuristicSoFar[i] = heuristic;
                            bestSoFar[i] = neighbor;
                            if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                failing = false;
                            }
                        }
                    }
                }
            }
        }
        // 无解 / 超时：回退到最好分段
        return bestSoFar();
    }

    /** 增量代价回退：选出各权重下推进最远（&gt; 5 格）的分段终点。 */
    private Optional<PathNode> bestSoFar() {
        if (startNode == null) {
            return Optional.empty();
        }
        double bestDist = 0;
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            if (bestSoFar[i] == null) {
                continue;
            }
            double dist = getDistFromStartSq(bestSoFar[i]);
            if (dist > bestDist) {
                bestDist = dist;
            }
            if (dist > MIN_DIST_PATH * MIN_DIST_PATH) {
                return Optional.of(bestSoFar[i]);
            }
        }
        return Optional.empty();
    }

    private double getDistFromStartSq(PathNode n) {
        int xDiff = n.x - startX;
        int yDiff = n.y - startY;
        int zDiff = n.z - startZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
    }

    /** 按 16×8×16 cell 分桶取（必要时创建）节点，移植自 Baritone 的 {@code getNodeAtPosition}。 */
    private PathNode getNodeAtPosition(int x, int y, int z, long hashCode) {
        long cellKey = ((long) ((x >> CELL_BITS_X) & 0xFFFFFFF) << 36)
                | ((long) ((z >> CELL_BITS_Z) & 0xFFFFFFF) << 8)
                | ((y >> CELL_BITS_Y) & 0xFF);
        PathNode[] cell;
        if (cellKey == prevCellKey) {
            cell = prevCell;
        } else {
            cell = cells.get(cellKey);
            if (cell == null) {
                cell = new PathNode[CELL_SIZE];
                cells.put(cellKey, cell);
            }
            prevCell = cell;
            prevCellKey = cellKey;
        }
        int index = ((y & ((1 << CELL_BITS_Y) - 1)) << (CELL_BITS_X + CELL_BITS_Z))
                | ((z & ((1 << CELL_BITS_Z) - 1)) << CELL_BITS_X)
                | (x & ((1 << CELL_BITS_X) - 1));
        PathNode node = cell[index];
        if (node == null) {
            node = new PathNode(x, y, z, goal);
            cell[index] = node;
        }
        return node;
    }
}
