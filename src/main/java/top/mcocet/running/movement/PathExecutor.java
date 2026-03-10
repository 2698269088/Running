package top.mcocet.running.movement;

import lombok.Getter;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.pathfinding.core.PathFinder;
import top.mcocet.running.pathfinding.core.PathNode;
import top.mcocet.running.pathfinding.goals.CoordinateGoal;
import top.mcocet.running.pathfinding.goals.Goal;
import top.mcocet.running.pathfinding.utils.Path;
import top.mcocet.running.pathfinding.utils.PathQualityAssessor;
import xin.bbtt.MovementSync;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class PathExecutor {
    private static PathExecutor instance;

    private static final Logger logger = LoggerFactory.getLogger("RunningPathExecutorSystem");
    
    // 最大偏离距离（格）
    private static final double MAX_DIST_FROM_PATH = 5.0;
    
    //  ticks 偏离阈值（参考 Baritone 的设计）
    private static final double MAX_TICKS_AWAY = 200;
    
    // 最大重试次数
    private static final int MAX_RETRIES = 3;

    @Getter
    private volatile Path currentPath;
    @Getter
    private volatile int currentPathIndex;
    private final AtomicBoolean isExecuting = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final MovementAdapter movementAdapter;
    private int retryCount = 0;  // 当前路径点的重试次数
    
    private PathExecutor() {
        this.movementAdapter = MovementAdapter.getInstance();
        this.currentPath = null;
        this.currentPathIndex = 0;
    }
    
    public static PathExecutor getInstance() {
        if (instance == null) {
            instance = new PathExecutor();
        }
        return instance;
    }
    
    public CompletableFuture<Optional<Path>> findAndExecutePath(Goal goal) {
        return findAndExecutePath(goal, 5000, 10000);
    }
    
    public CompletableFuture<Optional<Path>> findAndExecutePath(Goal goal, long primaryTimeout, long failureTimeout) {
        if (!movementAdapter.isAvailable()) {
            logger.error("错误：Movement系统不可用，无法进行路径规划");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // 检查当前位置是否合理
        Vector3d currentPos = movementAdapter.getCurrentPosition();
        
        if (currentPos.x == 0 && currentPos.y == 0 && currentPos.z == 0) {
            logger.error("错误：当前位置为(0,0,0)，MovementSync未能获取到有效位置信息");
            logger.error("诊断信息：MovementSync.entityId = " + movementAdapter.getEntityId());
            logger.error("建议解决方案：");
            logger.error("1. 重新连接服务器");
            logger.error("2. 确认MovementSync插件已正确加载");
            logger.error("3. 执行whereami命令确认位置同步状态");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // 如果正在执行路径，先停止当前执行
        if (isExecuting.get()) {
            stopExecution();
            // 等待一小段时间确保停止完成
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }
        
        Vector3d startPos = movementAdapter.getCurrentPosition();
        
        // 对于坐标目标且距离较远的情况，使用分段导航
        Vector3d targetPos = extractTargetPosition(goal);
        if (targetPos != null) {
            double distance = startPos.distance(targetPos);
            
            if (distance > 200) { // 距离超过200格时启用分段导航
                logger.info("检测到长距离目标(" + (int)distance + "格)，启用分段导航");
                return findAndExecutePathSegmented(goal, startPos, targetPos);
            }
        }
        
        // 正常寻路
        PathFinder finder = new PathFinder(startPos, goal);
        return finder.findPathAsync().thenCompose(pathOpt -> {
            if (pathOpt.isPresent()) {
                Path path = pathOpt.get();
                
                // 评估路径质量
                Vector3d extractedTargetPos = extractTargetPosition(goal);
                if (extractedTargetPos != null) {
                    PathQualityAssessor.QualityReport qualityReport = 
                        PathQualityAssessor.assessPathQuality(path, startPos, extractedTargetPos);

                    logger.info("路径质量评估: " + qualityReport);
                    
                    // 如果路径质量太差，考虑智能回退
                    if (PathQualityAssessor.shouldFallback(path, startPos, extractedTargetPos)) {
                        logger.info("路径质量不佳，启用智能回退机制");
                        return handlePoorQualityPath(path, goal, startPos);
                    }
                }
                
                return executePath(path).thenApply(v -> pathOpt);
            }
            return CompletableFuture.completedFuture(pathOpt);
        });
    }
    
    public CompletableFuture<Void> executePath(Path path) {
        if (path == null || path.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
                
        // 限制路径长度，避免执行过长的路径
        final int MAX_PATH_LENGTH = 500; // 降低最大节点数限制
        Path trimmedPath;
        if (path.size() > MAX_PATH_LENGTH) {
            // 截取前 MAX_PATH_LENGTH 个节点
            List<PathNode> trimmedNodes = new ArrayList<>();
            for (int i = 0; i < MAX_PATH_LENGTH && i < path.size(); i++) {
                trimmedNodes.add(path.getNode(i));
            }
            trimmedPath = new Path(path.getStartPos(), trimmedNodes);
            logger.info("Path trimmed from " + path.size() + " to " + trimmedPath.size() + " nodes");
        } else {
            trimmedPath = path;
        }
            
        if (!isExecuting.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Already executing a path"));
        }
            
        shouldStop.set(false);
        currentPath = trimmedPath;
        currentPathIndex = 0;
        retryCount = 0;  // 重置重试计数器
            
        // 在开始新路径前，先清除所有正在执行的移动和残留速度
        movementAdapter.cancelAllMovements();
        MovementSync.Instance.velocity.set(new Vector3d(0, 0, 0));
        logger.info("PathExecutor: 已清除所有移动和残留速度，开始执行新路径");
            
        return executePathStep().whenComplete((result, throwable) -> {
            isExecuting.set(false);
            currentPath = null;
            currentPathIndex = 0;
            retryCount = 0;  // 重置重试计数器
        });
    }
    
    private CompletableFuture<Void> executePathStep() {
        if (shouldStop.get()) {
            logger.info("PathExecutor: 收到停止信号");
            return CompletableFuture.completedFuture(null);
        }
        
        if (currentPath == null) {
            logger.info("PathExecutor: currentPath 为 null");
            return CompletableFuture.completedFuture(null);
        }
        
        if (currentPathIndex >= currentPath.size()) {
            logger.info("PathExecutor: 已到达路径终点 (index={}/{})", currentPathIndex, currentPath.size());
            return CompletableFuture.completedFuture(null);
        }
            
        if (!movementAdapter.isAvailable()) {
            logger.error("PathExecutor: Movement 系统不可用");
            return CompletableFuture.failedFuture(new IllegalStateException("Movement system unavailable"));
        }
        
        var currentNode = currentPath.getNode(currentPathIndex);
        Vector3d targetPos = currentNode.getPosition();
        Vector3d currentPos = movementAdapter.getCurrentPosition();
        
        // 检查当前位置是否在路径点附近（处理网络延迟导致的传送）
        double distance = currentPos.distance(targetPos);
        
        // 防止路径点过近导致振荡
        if (distance < 0.1) {
            logger.info("PathExecutor: 目标点过近 (距离={:.3f}, index={}/{})，跳过此点", 
                       String.format("%.3f", distance), currentPathIndex, currentPath.size());
            currentPathIndex++;
            return executePathStep();
        }
        
        if (distance < 0.5) {
            logger.info("PathExecutor: 已到达目标点附近 (距离={:.2f}, index={}/{})，前往下一个点", 
                       String.format("%.2f", distance), currentPathIndex, currentPath.size());
            currentPathIndex++;
            return executePathStep();
        }
        
        // 检查是否偏离路径太远
        if (distance > MAX_DIST_FROM_PATH) {
            logger.warn("PathExecutor: 偏离路径过远 (距离={:.2f})，尝试重新对齐...", 
                       String.format("%.2f", distance));
            // 不立即失败，而是继续尝试移动到目标
        }
            
        // 执行移动到目标点
        logger.info("PathExecutor: 开始移动到 ({}, {}, {}), 距离={:.2f}", 
                   targetPos.x, targetPos.y, targetPos.z, String.format("%.2f", distance));
        return movementAdapter.moveTo(targetPos).thenCompose(v -> {
            // 移动完成后，重新获取最新位置并检查是否到达
            Vector3d newPos = movementAdapter.getCurrentPosition();
            double newDistance = newPos.distance(targetPos);
            logger.info("PathExecutor: 移动完成，新位置距离目标={:.2f} (之前距离={:.2f})", 
                       String.format("%.2f", newDistance), String.format("%.2f", distance));
            
            // 如果移动后仍然离目标很远，说明移动失败了
            if (newDistance > MAX_DIST_FROM_PATH) {
                logger.warn("PathExecutor: 移动失败，偏离路径过远 (距离={:.2f})", 
                           String.format("%.2f", newDistance));
                // 尝试重试一次
                if (retryCount < MAX_RETRIES) {
                    retryCount++;
                    logger.info("PathExecutor: 第 {} 次重试 (最大{}次)", retryCount, MAX_RETRIES);
                    return executePathStep(); // 重新尝试移动到同一个点
                } else {
                    logger.error("PathExecutor: 重试{}次后仍然失败，放弃此路径点", MAX_RETRIES);
                    currentPathIndex++; // 跳过这个点
                    retryCount = 0;
                    return executePathStep();
                }
            }
            
            // 移动成功，重置重试计数器
            retryCount = 0;
            
            // 检查是否可以前往下一个点
            if (newDistance < 0.5) {
                logger.info("PathExecutor: 已到达目标点附近 (距离={:.2f})，前往下一个点", 
                           String.format("%.2f", newDistance));
                currentPathIndex++;
                return executePathStep();
            }
            
            // 否则继续移动到同一个点
            logger.info("PathExecutor: 尚未到达目标点 (距离={:.2f})，继续移动", 
                       String.format("%.2f", newDistance));
            return executePathStep();
        });
    }
    
    public void stopExecution() {
        shouldStop.set(true);
        movementAdapter.cancelAllMovements();
    }
    
    public boolean isExecuting() {
        return isExecuting.get();
    }
    
    /**
     * 从不同类型的Goal中提取目标位置
     */
    private Vector3d extractTargetPosition(Goal goal) {
        // 如果是我们的CoordinateGoal
        if (goal instanceof CoordinateGoal) {
            return goal.getTargetPosition();
        }
        
        // 使用反射处理Baritone的Goal类型
        try {
            String className = goal.getClass().getName();
            
            // 如果是Baritone的GoalXZ
            if (className.endsWith("GoalXZ")) {
                // 通过反射调用getX()和getZ()方法
                java.lang.reflect.Method getXMethod = goal.getClass().getMethod("getX");
                java.lang.reflect.Method getZMethod = goal.getClass().getMethod("getZ");
                int x = (Integer) getXMethod.invoke(goal);
                int z = (Integer) getZMethod.invoke(goal);
                return new Vector3d(x, 0, z); // Y坐标设为0
            }
            
            // 如果是Baritone的GoalBlock
            if (className.endsWith("GoalBlock")) {
                // 通过反射访问public字段
                java.lang.reflect.Field xField = goal.getClass().getField("x");
                java.lang.reflect.Field yField = goal.getClass().getField("y");
                java.lang.reflect.Field zField = goal.getClass().getField("z");
                int x = xField.getInt(goal);
                int y = yField.getInt(goal);
                int z = zField.getInt(goal);
                return new Vector3d(x, y, z);
            }
        } catch (Exception e) {
            // 静默处理反射异常
        }
        
        // fallback: 返回null让调用者决定如何处理
        return null;
    }
    
    public boolean isMovingTowards(Vector3d target) {
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            return false;
        }
        
        var currentNode = currentPath.getNode(currentPathIndex);
        Vector3d currentTarget = currentNode.getPosition();
        return currentTarget.distance(target) < 0.5;
    }
    
    public double getRemainingDistance() {
        if (currentPath == null || currentPathIndex >= currentPath.size()) {
            return 0;
        }
        
        Vector3d currentPos = movementAdapter.getCurrentPosition();
        var currentNode = currentPath.getNode(currentPathIndex);
        return currentPos.distance(currentNode.getPosition());
    }
    
    /**
     * 分段导航方法 - 将长距离目标分解为多个短距离段落
     */
    private CompletableFuture<Optional<Path>> findAndExecutePathSegmented(Goal finalGoal, Vector3d startPos, Vector3d targetPos) {
        // 计算总距离
        double totalDistance = startPos.distance(targetPos);
        int segmentCount = Math.max(2, (int)(totalDistance / 150)); // 每段约150格

        logger.info("将路径分为" + segmentCount + "段进行导航");
        
        // 创建中间目标点
        List<Vector3d> waypoints = new ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            double ratio = (double) i / segmentCount;
            Vector3d waypoint = new Vector3d(
                startPos.x + (targetPos.x - startPos.x) * ratio,
                startPos.y + (targetPos.y - startPos.y) * ratio,
                startPos.z + (targetPos.z - startPos.z) * ratio
            );
            waypoints.add(waypoint);
        }
        
        // 递归执行分段导航
        return executeSegmentedPath(waypoints, 0, finalGoal);
    }
    
    /**
     * 递归执行分段路径
     */
    private CompletableFuture<Optional<Path>> executeSegmentedPath(List<Vector3d> waypoints, int currentIndex, Goal finalGoal) {
        if (currentIndex >= waypoints.size()) {
            // 所有分段完成，执行到最后目标
            return findAndExecutePath(finalGoal, 3000, 6000);
        }
        
        Vector3d currentWaypoint = waypoints.get(currentIndex);
        logger.info("执行第" + (currentIndex + 1) + "段导航，目标: " +
                          String.format("(%.1f, %.1f, %.1f)", currentWaypoint.x, currentWaypoint.y, currentWaypoint.z));
        
        // 创建临时坐标目标
        Goal segmentGoal = new CoordinateGoal((int)currentWaypoint.x, (int)currentWaypoint.y, (int)currentWaypoint.z);
        
        // 寻找并执行当前段
        PathFinder finder = new PathFinder(movementAdapter.getCurrentPosition(), segmentGoal);
        return finder.findPathAsync(3000, 6000).thenCompose(pathOpt -> {
            if (pathOpt.isPresent()) {
                Path path = pathOpt.get();
                return executePath(path).thenCompose(v -> {
                    // 当前段完成后，延迟一段时间再执行下一段
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    
                    // 递归执行下一段
                    return executeSegmentedPath(waypoints, currentIndex + 1, finalGoal);
                });
            } else {
                logger.info("第" + (currentIndex + 1) + "段路径未找到，尝试直接导航到最终目标");
                return findAndExecutePath(finalGoal, 3000, 6000);
            }
        }).exceptionally(throwable -> {
            logger.info("第" + (currentIndex + 1) + "段执行出错: " + throwable.getMessage());
            return Optional.empty();
        });
    }
    
    /**
     * 处理质量较差的路径
     */
    private CompletableFuture<Optional<Path>> handlePoorQualityPath(Path poorPath, Goal originalGoal, Vector3d startPos) {
        logger.info("开始处理低质量路径...");
        
        // 方案1: 尝试分段导航
        if (originalGoal instanceof CoordinateGoal) {
            Vector3d targetPos = originalGoal.getTargetPosition();
            double distance = startPos.distance(targetPos);
            
            if (distance > 100) {
                logger.info("尝试分段导航策略");
                return findAndExecutePathSegmented(originalGoal, startPos, targetPos);
            }
        }
        
        // 方案2: 放宽搜索参数重试
        logger.info("放宽搜索参数重试");
        PathFinder relaxedFinder = new PathFinder(startPos, originalGoal);
        return relaxedFinder.findPathAsync(8000, 15000).thenCompose(relaxedPathOpt -> {
            if (relaxedPathOpt.isPresent()) {
                Path relaxedPath = relaxedPathOpt.get();
                PathQualityAssessor.QualityReport relaxedReport = 
                    PathQualityAssessor.assessPathQuality(relaxedPath, startPos, originalGoal.getTargetPosition());

                logger.info("放宽参数后路径质量: " + relaxedReport);
                
                if (!PathQualityAssessor.shouldFallback(relaxedPath, startPos, originalGoal.getTargetPosition())) {
                    return executePath(relaxedPath).thenApply(v -> relaxedPathOpt);
                }
            }
            
            // 方案3: 返回原始路径（尽管质量不佳）
            logger.info("所有优化方案均无效，使用原始路径");
            return executePath(poorPath).thenApply(v -> Optional.of(poorPath));
        });
    }
}