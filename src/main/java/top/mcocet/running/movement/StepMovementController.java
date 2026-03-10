package top.mcocet.running.movement;

import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.MovementSync;
import xin.bbtt.movements.WalkMovement;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Baritone 思想的分步移动控制器
 * 
 * 核心原理：
 * 1. 计算当前位置到目标点的方向向量
 * 2. 创建 WalkMovement 并添加到队列
 * 3. 等待移动完成后检查是否到达
 * 4. 如果未到达则重试或继续移动
 */
public class StepMovementController {
    private static final Logger logger = LoggerFactory.getLogger("RunningStepMovement");
    
    // 移动速度（格/秒）- 与 MovementSync 保持一致
    private static final double MOVEMENT_SPEED = 0.2159;
    
    // 到达判断阈值
    private static final double ARRIVAL_THRESHOLD = 0.5; // 距离小于此值认为已到达
    
    // 最大重试次数
    private static final int MAX_RETRIES = 3;
    
    // 当前移动状态
    private Vector3d targetPosition;
    private int retryCount;
    
    public StepMovementController() {
        this.retryCount = 0;
    }
    
    /**
     * 移动到目标位置
     * @param target 目标位置
     * @return CompletableFuture，移动完成时返回 true（成功）或 false（失败）
     */
    public CompletableFuture<Boolean> moveTo(Vector3d target) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Movement system is not available"));
        }
        
        this.targetPosition = new Vector3d(target);
        this.retryCount = 0;
        
        return executeMove();
    }
    
    /**
     * 执行单次移动
     */
    private CompletableFuture<Boolean> executeMove() {
        Vector3d currentPos = MovementSync.Instance.position.get();
        double distance = new Vector3d(targetPosition).distance(currentPos);
        
        // 检查是否已到达
        if (distance < ARRIVAL_THRESHOLD) {
            logger.info("StepMovement: 已到达目标，距离={:.2f}", String.format("%.2f", distance));
            return CompletableFuture.completedFuture(true);
        }
        
        // 强制清除残留速度
        clearVelocity();
        
        // 计算方向向量
        Vector3d direction = calculateDirection(currentPos, targetPosition);
        
        // 根据距离计算移动时间
        long duration = calculateDuration(distance);
        
        logger.info("StepMovement: 开始移动，目标=({}, {}, {}), 距离={:.2f}, 时间={}ms", 
                   String.format("%.2f", targetPosition.x),
                   String.format("%.2f", targetPosition.y),
                   String.format("%.2f", targetPosition.z),
                   String.format("%.2f", distance),
                   duration);
        logger.debug("StepMovement: 方向向量=({}, {}, {})", 
                    String.format("%.4f", direction.x),
                    String.format("%.4f", direction.y),
                    String.format("%.4f", direction.z));
        
        // 创建并添加移动
        WalkMovement movement = new WalkMovement(direction, duration);
        MovementSync.Instance.getMovementController().addMovement(movement);
        
        // 等待移动完成
        return waitForMovement(duration).thenCompose(v -> checkResult());
    }
    
    /**
     * 计算从起点到终点的方向向量
     */
    private Vector3d calculateDirection(Vector3d from, Vector3d to) {
        return new Vector3d(to).sub(from).normalize().mul(MOVEMENT_SPEED);
    }
    
    /**
     * 根据距离计算移动持续时间
     */
    private long calculateDuration(double distance) {
        // 理论时间 + 缓冲时间
        long theoreticalTime = (long) (distance / MOVEMENT_SPEED * 1000);
        long bufferTime = 1000; // 1 秒缓冲
        long duration = theoreticalTime + bufferTime;
        
        // 限制范围
        duration = Math.max(2000, Math.min(duration, 8000));
        
        return duration;
    }
    
    /**
     * 等待移动完成
     */
    private CompletableFuture<Void> waitForMovement(long duration) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        MovementSync.Instance.movementService.schedule(() -> {
            future.complete(null);
        }, duration, TimeUnit.MILLISECONDS);
        return future;
    }
    
    /**
     * 检查移动结果
     */
    private CompletableFuture<Boolean> checkResult() {
        Vector3d newPos = MovementSync.Instance.position.get();
        double newDistance = new Vector3d(targetPosition).distance(newPos);
        
        logger.info("StepMovement: 移动完成，新距离={:.2f}", String.format("%.2f", newDistance));
        
        // 检查是否到达
        if (newDistance < ARRIVAL_THRESHOLD) {
            return CompletableFuture.completedFuture(true);
        }
        
        // 检查是否需要重试
        if (retryCount < MAX_RETRIES) {
            retryCount++;
            logger.info("StepMovement: 未到达目标，第 {} 次重试（最大{}次）", retryCount, MAX_RETRIES);
            return executeMove();
        } else {
            logger.warn("StepMovement: 重试{}次后仍未到达，放弃", MAX_RETRIES);
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * 清除残留速度
     */
    private void clearVelocity() {
        Vector3d currentVel = MovementSync.Instance.velocity.get();
        if (currentVel.length() > 0.001) {
            logger.debug("StepMovement: 清除残留速度=({}, {}, {})", 
                        String.format("%.4f", currentVel.x),
                        String.format("%.4f", currentVel.y),
                        String.format("%.4f", currentVel.z));
            MovementSync.Instance.velocity.set(new Vector3d(0, 0, 0));
        }
    }
    
    /**
     * 检查系统是否可用
     */
    private boolean isAvailable() {
        // 只检查 MovementSync.Instance 和 position，不检查 movementService
        return MovementSync.Instance != null 
            && MovementSync.Instance.position != null;
    }
    
    /**
     * 获取剩余距离
     */
    public double getRemainingDistance() {
        if (targetPosition == null) {
            return 0;
        }
        Vector3d currentPos = MovementSync.Instance.position.get();
        return new Vector3d(targetPosition).distance(currentPos);
    }
    
    /**
     * 是否正在移动
     */
    public boolean isMoving() {
        return targetPosition != null && retryCount <= MAX_RETRIES;
    }
}
