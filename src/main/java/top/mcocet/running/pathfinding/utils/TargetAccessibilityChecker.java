package top.mcocet.running.pathfinding.utils;

import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.pathfinding.goals.CoordinateGoal;
import top.mcocet.running.pathfinding.goals.Goal;

/**
 * 目标可达性检测工具类
 * 在开始完整路径搜索前，快速评估目标是否可能可达
 */
public class TargetAccessibilityChecker {
    private static final Logger logger = LoggerFactory.getLogger("RunningPCSystem");

    /**
     * 快速检查目标是否可能可达
     * @param startPos 起始位置
     * @param goal 目标
     * @return 是否可能可达
     */
    public static boolean isTargetAccessible(Vector3d startPos, Goal goal) {
        // 安全检查输入参数
        if (startPos == null) {
            logger.warn("错误：起始位置为null");
            return false;
        }
        
        // 获取目标的大致位置
        Vector3d targetPos = goal.getTargetPosition();
        if (targetPos == null) {
            logger.warn("警告：目标位置为null，假设可达");
            return true; // 如果目标没有具体位置，假设可达
        }
        
        // 计算直线距离
        double straightLineDistance = startPos.distance(targetPos);
        
        // 基本距离检查（适度放宽限制）
        if (straightLineDistance > 5000) { // 从1000格放宽到5000格
            logger.warn("目标距离过远(" + (int)straightLineDistance + "格)，可能不可达");
            return false;
        }
        
        // 高度差检查（适度放宽限制）
        double heightDiff = Math.abs(startPos.y - targetPos.y);
        if (heightDiff > 500) { // 从200格放宽到500格
            logger.warn("目标高度差过大(" + (int)heightDiff + "格)，可能不可达");
            return false;
        }
        
        // 粗略的世界边界检查
        if (Math.abs(targetPos.x) > 30000000 || Math.abs(targetPos.z) > 30000000) {
            logger.warn("目标超出世界边界");
            return false;
        }
        
        // 如果是坐标目标，进行更详细的检查
        if (goal instanceof CoordinateGoal) {
            return isCoordinateTargetAccessible(startPos, targetPos);
        }
        
        return true;
    }
    
    /**
     * 对坐标目标进行更详细的可达性检查
     */
    private static boolean isCoordinateTargetAccessible(Vector3d startPos, Vector3d targetPos) {
        // 计算水平距离
        double horizontalDistance = Math.sqrt(
            Math.pow(targetPos.x - startPos.x, 2) + 
            Math.pow(targetPos.z - startPos.z, 2)
        );
        
        // 水平距离过大的情况
        if (horizontalDistance > 500) {
            logger.warn("水平距离过大(" + (int)horizontalDistance + "格)，建议分段导航");
            return false;
        }
        
        return true;
    }
    
    /**
     * 估算到达目标所需的时间（基于简单移动模型）
     * @param startPos 起始位置
     * @param goal 目标
     * @return 估算时间（毫秒），-1表示不可达
     */
    public static long estimateTravelTime(Vector3d startPos, Goal goal) {
        if (!isTargetAccessible(startPos, goal)) {
            return -1;
        }
        
        Vector3d targetPos = goal.getTargetPosition();
        if (targetPos == null) {
            return -1;
        }
        
        // 计算直线距离
        double distance = startPos.distance(targetPos);
        
        // 简单的移动速度估算（假设约4格/秒）
        double speed = 4.0; // 格/秒
        
        // 考虑地形复杂度的系数
        double complexityFactor = estimateTerrainComplexity(startPos, targetPos);
        
        // 计算估算时间（毫秒）
        long estimatedTime = (long) ((distance / speed) * complexityFactor * 1000);
        
        return estimatedTime;
    }
    
    /**
     * 估算地形复杂度系数
     */
    private static double estimateTerrainComplexity(Vector3d startPos, Vector3d targetPos) {
        double horizontalDistance = Math.sqrt(
            Math.pow(targetPos.x - startPos.x, 2) + 
            Math.pow(targetPos.z - startPos.z, 2)
        );
        
        double verticalDistance = Math.abs(targetPos.y - startPos.y);
        
        // 基础复杂度
        double complexity = 1.0;
        
        // 垂直移动增加复杂度
        if (verticalDistance > 0) {
            complexity += verticalDistance * 0.1; // 每格垂直距离增加10%复杂度
        }
        
        // 对角线移动略微降低复杂度
        if (horizontalDistance > 0 && verticalDistance > 0) {
            complexity *= 0.9;
        }
        
        // 限制复杂度范围
        return Math.max(1.0, Math.min(5.0, complexity));
    }
    
    /**
     * 建议的路径策略
     */
    public static PathStrategy suggestPathStrategy(Vector3d startPos, Goal goal) {
        if (!isTargetAccessible(startPos, goal)) {
            return PathStrategy.UNREACHABLE;
        }
        
        Vector3d targetPos = goal.getTargetPosition();
        if (targetPos == null) {
            return PathStrategy.NORMAL;
        }
        
        double distance = startPos.distance(targetPos);
        
        if (distance > 300) {
            return PathStrategy.LONG_DISTANCE;
        } else if (distance > 100) {
            return PathStrategy.MEDIUM_DISTANCE;
        } else {
            return PathStrategy.NORMAL;
        }
    }
    
    /**
     * 路径策略枚举
     */
    public enum PathStrategy {
        NORMAL("正常寻路"),
        MEDIUM_DISTANCE("中距离分段"),
        LONG_DISTANCE("长距离分段"),
        UNREACHABLE("目标不可达");
        
        private final String description;
        
        PathStrategy(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
}