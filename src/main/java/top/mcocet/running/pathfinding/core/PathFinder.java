package top.mcocet.running.pathfinding.core;

import lombok.Getter;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.pathfinding.goals.Goal;
import top.mcocet.running.pathfinding.utils.OpenSet;
import top.mcocet.running.pathfinding.utils.Path;
import top.mcocet.running.pathfinding.utils.PrecomputationCache;
import top.mcocet.running.pathfinding.utils.SmartAvoidanceSystem;
import top.mcocet.running.pathfinding.utils.TargetAccessibilityChecker;
import top.mcocet.running.utils.BetterBlockPos;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PathFinder {
    private static final Logger logger = LoggerFactory.getLogger("RunningMovementAdapterSystem");

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    
    @Getter
    private final Vector3d startPos;
    @Getter
    private final Goal goal;
    private final Map<Long, PathNode> nodeMap;
    private final SmartAvoidanceSystem avoidanceSystem;
    private final PrecomputationCache precomputationCache;
    private volatile boolean cancelled = false;
    
    public PathFinder(Vector3d startPos, Goal goal) {
        this.startPos = startPos;
        this.goal = goal;
        this.nodeMap = new HashMap<>();
        this.avoidanceSystem = new SmartAvoidanceSystem();
        this.precomputationCache = new PrecomputationCache();
        
        // 安全检查起始位置
        if (startPos == null) {
            throw new IllegalArgumentException("起始位置不能为null");
        }
        
        // 预热起始位置周围的缓存（使用bot的实际位置）
        BetterBlockPos startPosBlock = new BetterBlockPos(startPos);
        
        // 检查坐标是否合理（避免使用(0,0,0)这样的默认值）
        if (startPosBlock.x == 0 && startPosBlock.y == 0 && startPosBlock.z == 0) {
            System.out.println("警告：检测到起始位置为(0,0,0)，可能是Movement系统未准备好");
        }
        
        precomputationCache.warmupCache(startPosBlock, 10);
        logger.info("预热缓存区域: (" + startPosBlock.x + ", " + startPosBlock.y + ", " + startPosBlock.z + ") 半径: 10");
    }
    
    public CompletableFuture<Optional<Path>> findPathAsync() {
        return CompletableFuture.supplyAsync(this::findPath, executor);
    }
    
    public CompletableFuture<Optional<Path>> findPathAsync(long primaryTimeoutMs, long failureTimeoutMs) {
        return CompletableFuture.supplyAsync(() -> findPath(primaryTimeoutMs, failureTimeoutMs), executor);
    }
    
    public Optional<Path> findPath() {
        return findPath(5000, 10000); // 默认超时时间
    }
    
    public Optional<Path> findPath(long primaryTimeoutMs, long failureTimeoutMs) {
        cancelled = false;
        nodeMap.clear();
        
        // 首先进行目标可达性检查
        logger.info("开始可达性检查...");
        boolean isAccessible = TargetAccessibilityChecker.isTargetAccessible(startPos, goal);
        logger.info("目标可达性检查结果: " + isAccessible);
        
        TargetAccessibilityChecker.PathStrategy strategy = 
            TargetAccessibilityChecker.suggestPathStrategy(startPos, goal);
        
        if (strategy == TargetAccessibilityChecker.PathStrategy.UNREACHABLE) {
            logger.info("目标被判定为不可达，取消路径搜索");
            return Optional.empty();
        }

        logger.info("路径策略: " + strategy.getDescription());
        
        // 输出起始位置和目标位置信息
        Vector3d targetPos = goal.getTargetPosition();
        if (targetPos != null) {
            double distance = startPos.distance(targetPos);
            logger.info("起始位置: " + String.format("(%.2f, %.2f, %.2f)", startPos.x, startPos.y, startPos.z));
            logger.info("目标位置: " + String.format("(%.2f, %.2f, %.2f)", targetPos.x, targetPos.y, targetPos.z));
            logger.info("直线距离: " + String.format("%.2f", distance) + " 格");
        }
        
        // 安全检查起始位置
        if (startPos == null) {
            logger.error("错误：起始位置为null");
            return Optional.empty();
        }
        
        PathNode startNode = getNodeAtPosition(startPos);
        startNode.setCost(0);
        double heuristicValue = goal.heuristic(startNode);
        
        // 检查启发式值是否合理
        if (Double.isNaN(heuristicValue) || Double.isInfinite(heuristicValue) || heuristicValue > 1000000) {
            logger.warn("错误：启发式值异常大: " + heuristicValue);
            return Optional.empty();
        }
        
        startNode.setEstimatedCostToGoal(heuristicValue);
        startNode.setCombinedCost(startNode.getCost() + startNode.getEstimatedCostToGoal());
        
        OpenSet openSet = new OpenSet();
        openSet.insert(startNode);
        
        long startTime = System.currentTimeMillis();
        long primaryTimeout = startTime + primaryTimeoutMs;
        long failureTimeout = startTime + failureTimeoutMs;
        
        PathNode bestNode = startNode;
        double bestHeuristic = startNode.getEstimatedCostToGoal();
        int nodeCount = 0;
        final int MAX_NODES = 5000; // 降低最大节点数限制
        final double MAX_DISTANCE_SQ = 250000; // 降低最大搜索距离(500格)
        final int MAX_PATH_NODES = 1000; // 限制最终路径的最大节点数
        
        // 根据策略调整参数
        int adjustedMaxNodes = MAX_NODES;
        double adjustedMaxDistanceSq = MAX_DISTANCE_SQ;
        
        switch (strategy) {
            case LONG_DISTANCE:
                adjustedMaxNodes = 3000; // 进一步降低长距离搜索的节点数
                adjustedMaxDistanceSq = 100000; // 限制为316格
                logger.info("长距离模式: 限制搜索范围至316格");
                break;
            case MEDIUM_DISTANCE:
                adjustedMaxNodes = 4000;
                adjustedMaxDistanceSq = 160000; // 限制为400格
                logger.info("中距离模式: 限制搜索范围至400格");
                break;
            default:
                // NORMAL模式使用默认值
                break;
        }
        
        while (!openSet.isEmpty() && !cancelled && nodeCount < adjustedMaxNodes) {
            long currentTime = System.currentTimeMillis();
            if (currentTime > failureTimeout || (!isFailing(bestNode) && currentTime > primaryTimeout)) {
                break;
            }
            
            PathNode currentNode = openSet.removeLowest();
            nodeCount++;
            
            // 提前终止条件：如果最佳节点的启发式值过大，说明目标可能不可达
            if (bestHeuristic > Math.sqrt(adjustedMaxDistanceSq) * 2) {
                logger.info("目标可能不可达，提前终止搜索");
                break;
            }
            
            // 距离限制检查（使用优化的距离计算）
            double currentDistSq = currentNode.distanceSquared(startNode);
            if (currentDistSq > adjustedMaxDistanceSq) {
                continue; // 跳过超出距离限制的节点
            }
            
            // 启发式值检查：如果当前节点的估计成本过高，跳过
            if (currentNode.getEstimatedCostToGoal() > Math.sqrt(adjustedMaxDistanceSq) * 1.5) {
                continue;
            }
            
            if (goal.isInGoal(currentNode)) {
                List<PathNode> fullPath = buildPath(currentNode);
                // 检查路径长度是否合理
                if (fullPath.size() > MAX_PATH_NODES) {
                    logger.info("找到的路径过长(" + fullPath.size() + "节点)，进行截断处理");
                    fullPath = fullPath.subList(0, MAX_PATH_NODES);
                }
                logger.info("Path found with " + nodeCount + " nodes explored, path length: " + fullPath.size());
                return Optional.of(new Path(startPos, fullPath));
            }
            
            if (currentNode.getEstimatedCostToGoal() < bestHeuristic) {
                bestHeuristic = currentNode.getEstimatedCostToGoal();
                bestNode = currentNode;
            }
            
            expandNode(currentNode, openSet);
        }
        
        if (cancelled) {
            return Optional.empty();
        }
        
        List<PathNode> bestPath = buildPath(bestNode);
        logger.info("Path search stopped after " + nodeCount + " nodes, best heuristic: " + bestHeuristic + ", best path length: " + bestPath.size());
        
        // 如果最佳路径也过长，返回空结果
        if (bestPath.size() > MAX_PATH_NODES * 2) {
            logger.info("最佳路径仍然过长(" + bestPath.size() + "节点)，判定为目标不可达");
            return Optional.empty();
        }
        
        // 返回最佳路径
        return Optional.of(new Path(startPos, bestPath));
    }
    
    private void expandNode(PathNode node, OpenSet openSet) {
        // 基础移动：前后左右
        expandDirection(node, 1, 0, 0, openSet);
        expandDirection(node, -1, 0, 0, openSet);
        expandDirection(node, 0, 0, 1, openSet);
        expandDirection(node, 0, 0, -1, openSet);
        
        // 对角线移动
        expandDirection(node, 1, 0, 1, openSet);
        expandDirection(node, 1, 0, -1, openSet);
        expandDirection(node, -1, 0, 1, openSet);
        expandDirection(node, -1, 0, -1, openSet);
        
        // 跳跃移动
        expandDirection(node, 1, 1, 0, openSet);
        expandDirection(node, -1, 1, 0, openSet);
        expandDirection(node, 0, 1, 1, openSet);
        expandDirection(node, 0, 1, -1, openSet);
        
        // 下降移动
        expandDirection(node, 0, -1, 0, openSet);
    }
    
    private void expandDirection(PathNode node, int dx, int dy, int dz, OpenSet openSet) {
        int newX = node.getX() + dx;
        int newY = node.getY() + dy;
        int newZ = node.getZ() + dz;
        
        // 简单的边界检查和障碍物检查（实际应用中需要连接到世界数据）
        if (newX < -30000000 || newX > 30000000 || newZ < -30000000 || newZ > 30000000) {
            return;
        }
        
        // 检查是否可以移动到该位置（简化版）
        if (!canMoveTo(newX, newY, newZ)) {
            return;
        }
        
        // 避让系统检查
        BetterBlockPos newPos = new BetterBlockPos(newX, newY, newZ);
        if (avoidanceSystem.isInAvoidanceZone(newPos)) {
            // 获取避让信息用于调试
            var avoidances = avoidanceSystem.getNearbyAvoidances(newPos, 3.0);
            if (!avoidances.isEmpty()) {
                // 可以选择完全避开或者增加成本
                double avoidanceCoeff = avoidanceSystem.getAvoidanceCoefficient(newPos);
                if (avoidanceCoeff > 5.0) { // 严重威胁，完全避开
                    return;
                }
                // 轻微威胁，增加移动成本
            }
        }
        
        PathNode neighbor = getNodeAtPosition(newX, newY, newZ);
        double moveCost = calculateMoveCost(node, neighbor, dx, dy, dz);
        
        if (moveCost >= Double.POSITIVE_INFINITY || Double.isNaN(moveCost)) {
            return;
        }
        
        double tentativeCost = node.getCost() + moveCost;
        
        if (tentativeCost < neighbor.getCost()) {
            neighbor.setPrevious(node);
            neighbor.setCost(tentativeCost);
            
            // 安全计算启发式值
            double neighborHeuristic = goal.heuristic(neighbor);
            if (Double.isNaN(neighborHeuristic) || Double.isInfinite(neighborHeuristic) || neighborHeuristic > 1000000) {
                return; // 跳过异常的启发式值
            }
            
            neighbor.setEstimatedCostToGoal(neighborHeuristic);
            neighbor.setCombinedCost(neighbor.getCost() + neighbor.getEstimatedCostToGoal());
            
            if (neighbor.isOpen()) {
                openSet.update(neighbor);
            } else {
                openSet.insert(neighbor);
            }
        }
    }
    
    private double calculateMoveCost(PathNode from, PathNode to, int dx, int dy, int dz) {
        // 首先检查缓存
        Double cachedCost = precomputationCache.getCachedMoveCost(from, to);
        if (cachedCost != null) {
            return cachedCost;
        }
        
        // 基础移动成本
        double cost = Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        // 垂直移动额外成本
        if (dy != 0) {
            cost += 1.0; // 跳跃或下降的成本
        }
        
        // 对角线移动稍微便宜一点
        if (dx != 0 && dz != 0) {
            cost *= 0.95;
        }
        
        // 应用避让系统调整
        cost = avoidanceSystem.applyAvoidanceToCost(cost, from.getBetterPos(), to.getBetterPos());
        
        // 缓存计算结果
        precomputationCache.cacheMoveCost(from, to, cost);
        
        return cost;
    }
    
    private boolean canMoveTo(int x, int y, int z) {
        BetterBlockPos pos = new BetterBlockPos(x, y, z);
        
        // 首先检查缓存
        Boolean cachedResult = precomputationCache.getCachedWalkability(pos);
        if (cachedResult != null) {
            return cachedResult;
        }
        
        // 简化的可移动性检查
        // 实际应用中应该检查方块类型、碰撞箱等
        boolean result = y >= 0 && y <= 255; // 基本的高度限制
        
        // 缓存结果
        precomputationCache.cacheWalkability(pos, result);
        
        return result;
    }
    
    private boolean isFailing(PathNode node) {
        // 简单的距离检查判断是否还在有效搜索范围内
        double distSq = node.getPosition().distanceSquared(startPos);
        return distSq < 25; // 如果距离起点小于5格，则认为还在失败区域内
    }
    
    private PathNode getNodeAtPosition(Vector3d pos) {
        return getNodeAtPosition((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }
    
    private PathNode getNodeAtPosition(int x, int y, int z) {
        long hash = (((long) x) << 32) | (((long) z) << 16) | (y & 0xFFFF);
        return nodeMap.computeIfAbsent(hash, k -> new PathNode(x, y, z));
    }
    
    private List<PathNode> buildPath(PathNode endNode) {
        List<PathNode> path = new ArrayList<>();
        PathNode current = endNode;
        
        while (current != null) {
            path.add(0, current); // 添加到开头
            current = current.getPrevious();
        }
        
        return path;
    }
    
    public void cancel() {
        cancelled = true;
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
}