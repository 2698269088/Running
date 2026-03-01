package top.mcocet.running.pathfinding.utils;

import org.joml.Vector3d;
import top.mcocet.running.pathfinding.core.PathNode;

import java.util.List;

/**
 * 路径质量评估工具类
 * 用于评估找到的路径的质量并提供改进建议
 */
public class PathQualityAssessor {
    
    /**
     * 路径质量评估结果
     */
    public static class QualityReport {
        private final PathQuality quality;
        private final String recommendation;
        private final double score;
        private final String details;
        
        public QualityReport(PathQuality quality, String recommendation, double score, String details) {
            this.quality = quality;
            this.recommendation = recommendation;
            this.score = score;
            this.details = details;
        }
        
        // Getters
        public PathQuality getQuality() { return quality; }
        public String getRecommendation() { return recommendation; }
        public double getScore() { return score; }
        public String getDetails() { return details; }
        
        @Override
        public String toString() {
            return String.format("质量: %s (评分: %.1f) - %s", quality.getDescription(), score, recommendation);
        }
    }
    
    /**
     * 路径质量等级枚举
     */
    public enum PathQuality {
        EXCELLENT("优秀", 0.9),
        GOOD("良好", 0.7),
        ACCEPTABLE("可接受", 0.5),
        POOR("较差", 0.3),
        UNACCEPTABLE("不可接受", 0.0);
        
        private final String description;
        private final double threshold;
        
        PathQuality(String description, double threshold) {
            this.description = description;
            this.threshold = threshold;
        }
        
        public String getDescription() { return description; }
        public double getThreshold() { return threshold; }
    }
    
    /**
     * 评估路径质量
     * @param path 要评估的路径
     * @param startPos 起始位置
     * @param targetPos 目标位置
     * @return 质量报告
     */
    public static QualityReport assessPathQuality(Path path, Vector3d startPos, Vector3d targetPos) {
        if (path == null || path.isEmpty()) {
            return new QualityReport(PathQuality.UNACCEPTABLE, "路径为空", 0.0, "未找到有效路径");
        }
        
        List<PathNode> nodes = path.getNodes();
        int nodeCount = nodes.size();
        double straightLineDistance = startPos.distance(targetPos);
        double actualPathDistance = calculateActualPathDistance(nodes);
        
        // 计算各种质量指标
        double efficiencyScore = calculateEfficiencyScore(straightLineDistance, actualPathDistance);
        double complexityScore = calculateComplexityScore(nodes);
        double lengthScore = calculateLengthScore(nodeCount);
        
        // 综合评分
        double overallScore = (efficiencyScore * 0.5 + complexityScore * 0.3 + lengthScore * 0.2);
        
        // 确定质量等级
        PathQuality quality = determineQuality(overallScore);
        
        // 生成建议
        String recommendation = generateRecommendation(quality, nodeCount, efficiencyScore, complexityScore);
        String details = generateDetails(nodeCount, straightLineDistance, actualPathDistance, efficiencyScore);
        
        return new QualityReport(quality, recommendation, overallScore, details);
    }
    
    /**
     * 计算实际路径距离
     */
    private static double calculateActualPathDistance(List<PathNode> nodes) {
        double totalDistance = 0;
        for (int i = 1; i < nodes.size(); i++) {
            Vector3d prev = nodes.get(i-1).getPosition();
            Vector3d curr = nodes.get(i).getPosition();
            totalDistance += prev.distance(curr);
        }
        return totalDistance;
    }
    
    /**
     * 计算效率分数（越接近1越好）
     */
    private static double calculateEfficiencyScore(double straightLineDistance, double actualPathDistance) {
        if (straightLineDistance <= 0) return 1.0;
        double ratio = actualPathDistance / straightLineDistance;
        
        // 效率分数计算：理想情况下ratio=1，随着偏离程度增加而降低
        if (ratio <= 1.1) return 1.0;          // 非常高效
        if (ratio <= 1.5) return 0.9 - (ratio - 1.1) * 0.5;  // 高效
        if (ratio <= 2.0) return 0.7 - (ratio - 1.5) * 0.4;  // 中等
        if (ratio <= 3.0) return 0.5 - (ratio - 2.0) * 0.2;  // 较差
        return Math.max(0.0, 0.3 - (ratio - 3.0) * 0.1);     // 很差
    }
    
    /**
     * 计算复杂度分数（考虑垂直移动和转向）
     */
    private static double calculateComplexityScore(List<PathNode> nodes) {
        if (nodes.size() < 3) return 1.0;
        
        int verticalMoves = 0;
        int sharpTurns = 0;
        
        // 统计垂直移动和急转弯
        for (int i = 1; i < nodes.size(); i++) {
            PathNode prev = nodes.get(i-1);
            PathNode curr = nodes.get(i);
            
            // 垂直移动检测
            if (Math.abs(curr.getY() - prev.getY()) > 0) {
                verticalMoves++;
            }
            
            // 急转弯检测（连续方向改变超过90度）
            if (i > 1) {
                PathNode prevPrev = nodes.get(i-2);
                if (isSharpTurn(prevPrev, prev, curr)) {
                    sharpTurns++;
                }
            }
        }
        
        // 复杂度计算
        double verticalRatio = (double) verticalMoves / nodes.size();
        double turnRatio = (double) sharpTurns / nodes.size();
        
        // 垂直移动影响较小，急转弯影响较大
        return Math.max(0.0, 1.0 - verticalRatio * 0.3 - turnRatio * 0.7);
    }
    
    /**
     * 判断是否为急转弯
     */
    private static boolean isSharpTurn(PathNode a, PathNode b, PathNode c) {
        Vector3d vec1 = new Vector3d(b.getX() - a.getX(), 0, b.getZ() - a.getZ());
        Vector3d vec2 = new Vector3d(c.getX() - b.getX(), 0, c.getZ() - b.getZ());
        
        if (vec1.length() == 0 || vec2.length() == 0) return false;
        
        vec1.normalize();
        vec2.normalize();
        
        // 计算夹角余弦值
        double dotProduct = vec1.dot(vec2);
        return dotProduct < -0.5; // 夹角大于120度视为急转弯
    }
    
    /**
     * 计算长度分数
     */
    private static double calculateLengthScore(int nodeCount) {
        if (nodeCount <= 100) return 1.0;      // 很短
        if (nodeCount <= 300) return 0.9;      // 短
        if (nodeCount <= 500) return 0.7;      // 中等
        if (nodeCount <= 800) return 0.5;      // 长
        if (nodeCount <= 1000) return 0.3;     // 很长
        return 0.1;                            // 极长
    }
    
    /**
     * 确定质量等级
     */
    private static PathQuality determineQuality(double score) {
        if (score >= PathQuality.EXCELLENT.getThreshold()) return PathQuality.EXCELLENT;
        if (score >= PathQuality.GOOD.getThreshold()) return PathQuality.GOOD;
        if (score >= PathQuality.ACCEPTABLE.getThreshold()) return PathQuality.ACCEPTABLE;
        if (score >= PathQuality.POOR.getThreshold()) return PathQuality.POOR;
        return PathQuality.UNACCEPTABLE;
    }
    
    /**
     * 生成建议
     */
    private static String generateRecommendation(PathQuality quality, int nodeCount, 
                                               double efficiencyScore, double complexityScore) {
        switch (quality) {
            case EXCELLENT:
                return "路径质量优秀，可以直接使用";
            case GOOD:
                return "路径质量良好，适合执行";
            case ACCEPTABLE:
                if (nodeCount > 500) {
                    return "路径较长但可接受，建议分段执行";
                }
                return "路径质量一般，但仍可使用";
            case POOR:
                if (efficiencyScore < 0.5) {
                    return "路径效率较低，可能存在更好的路线";
                }
                if (complexityScore < 0.5) {
                    return "路径过于复杂，建议寻找替代路线";
                }
                return "路径质量较差，谨慎使用";
            case UNACCEPTABLE:
            default:
                return "路径质量不可接受，建议重新规划或手动导航";
        }
    }
    
    /**
     * 生成详细信息
     */
    private static String generateDetails(int nodeCount, double straightLineDistance, 
                                        double actualPathDistance, double efficiencyScore) {
        return String.format("节点数: %d, 直线距离: %.1f, 实际距离: %.1f, 效率: %.1f%%", 
                           nodeCount, straightLineDistance, actualPathDistance, efficiencyScore * 100);
    }
    
    /**
     * 检查是否需要智能回退
     */
    public static boolean shouldFallback(Path path, Vector3d startPos, Vector3d targetPos) {
        QualityReport report = assessPathQuality(path, startPos, targetPos);
        return report.getQuality() == PathQuality.UNACCEPTABLE || 
               report.getQuality() == PathQuality.POOR;
    }
}