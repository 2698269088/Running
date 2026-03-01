package top.mcocet.running.pathfinding.utils;

import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.utils.BetterBlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能避让系统 - 借鉴Baritone的Avoidance设计理念
 * 用于在路径规划中避开危险区域、怪物和其他障碍物
 */
public class SmartAvoidanceSystem {
    private static final Logger logger = LoggerFactory.getLogger("RunningPCSystem");

    private final List<AvoidanceZone> avoidanceZones;
    private final double defaultAvoidanceRadius;
    private final double defaultAvoidanceCoefficient;
    
    public SmartAvoidanceSystem() {
        this.avoidanceZones = new ArrayList<>();
        this.defaultAvoidanceRadius = 5.0;
        this.defaultAvoidanceCoefficient = 2.0;
    }
    
    /**
     * 添加避让区域
     */
    public void addAvoidanceZone(Vector3d center, double radius, double coefficient, AvoidanceType type) {
        avoidanceZones.add(new AvoidanceZone(
            new BetterBlockPos(center),
            radius,
            coefficient,
            type
        ));
        logger.debug("添加避让区域: " + type.getDescription() + " at " +
                          String.format("(%.1f, %.1f, %.1f)", center.x, center.y, center.z));
    }
    
    /**
     * 添加基于坐标的避让区域
     */
    public void addAvoidanceZone(int x, int y, int z, double radius, double coefficient, AvoidanceType type) {
        avoidanceZones.add(new AvoidanceZone(
            new BetterBlockPos(x, y, z),
            radius,
            coefficient,
            type
        ));
        logger.debug("添加避让区域: " + type.getDescription() + " at (" + x + ", " + y + ", " + z + ")");
    }
    
    /**
     * 移除特定类型的避让区域
     */
    public void removeAvoidanceType(AvoidanceType type) {
        avoidanceZones.removeIf(zone -> zone.type == type);
        logger.debug("移除避让类型: " + type.getDescription());
    }
    
    /**
     * 清除所有避让区域
     */
    public void clearAllAvoidance() {
        avoidanceZones.clear();
        logger.debug("清除所有避让区域");
    }
    
    /**
     * 计算指定位置的避让系数
     * @param pos 要检查的位置
     * @return 避让系数（1.0表示无影响，>1.0表示需要避开）
     */
    public double getAvoidanceCoefficient(BetterBlockPos pos) {
        double totalCoefficient = 1.0;
        
        for (AvoidanceZone zone : avoidanceZones) {
            double distance = pos.distance(zone.center);
            
            // 如果在避让半径内
            if (distance <= zone.radius) {
                // 根据距离计算影响系数（越近影响越大）
                double influence = 1.0 - (distance / zone.radius);
                double zoneCoefficient = 1.0 + (zone.coefficient - 1.0) * influence;
                totalCoefficient = Math.max(totalCoefficient, zoneCoefficient);
            }
        }
        
        return totalCoefficient;
    }
    
    /**
     * 应用避让系数到移动成本
     * @param baseCost 基础移动成本
     * @param from 起始位置
     * @param to 目标位置
     * @return 调整后的移动成本
     */
    public double applyAvoidanceToCost(double baseCost, BetterBlockPos from, BetterBlockPos to) {
        // 计算路径中点的避让系数
        BetterBlockPos midPoint = new BetterBlockPos(
            (from.x + to.x) / 2,
            (from.y + to.y) / 2,
            (from.z + to.z) / 2
        );
        
        double avoidanceCoefficient = getAvoidanceCoefficient(midPoint);
        return baseCost * avoidanceCoefficient;
    }
    
    /**
     * 检查位置是否在任何避让区域内
     */
    public boolean isInAvoidanceZone(BetterBlockPos pos) {
        return getAvoidanceCoefficient(pos) > 1.0;
    }
    
    /**
     * 获取附近的避让区域信息
     */
    public List<AvoidanceInfo> getNearbyAvoidances(BetterBlockPos pos, double searchRadius) {
        List<AvoidanceInfo> nearby = new ArrayList<>();
        
        for (AvoidanceZone zone : avoidanceZones) {
            double distance = pos.distance(zone.center);
            if (distance <= searchRadius + zone.radius) {
                nearby.add(new AvoidanceInfo(
                    zone.type,
                    zone.center,
                    zone.radius,
                    zone.coefficient,
                    distance
                ));
            }
        }
        
        return nearby;
    }
    
    /**
     * 预设的危险类型添加方法
     */
    public void addDangerousArea(Vector3d center) {
        addAvoidanceZone(center, 8.0, 5.0, AvoidanceType.DANGER);
    }
    
    public void addMobAvoidance(Vector3d center) {
        addAvoidanceZone(center, 6.0, 3.0, AvoidanceType.MOB);
    }
    
    public void addLavaAvoidance(Vector3d center) {
        addAvoidanceZone(center, 10.0, 10.0, AvoidanceType.LAVA);
    }
    
    public void addFallRiskArea(Vector3d center) {
        addAvoidanceZone(center, 4.0, 4.0, AvoidanceType.FALL_RISK);
    }
    
    /**
     * 获取统计信息
     */
    public AvoidanceStats getStats() {
        int[] typeCounts = new int[AvoidanceType.values().length];
        
        for (AvoidanceZone zone : avoidanceZones) {
            typeCounts[zone.type.ordinal()]++;
        }
        
        return new AvoidanceStats(
            avoidanceZones.size(),
            typeCounts
        );
    }
    
    /**
     * 避让区域类
     */
    private static class AvoidanceZone {
        final BetterBlockPos center;
        final double radius;
        final double coefficient;
        final AvoidanceType type;
        
        AvoidanceZone(BetterBlockPos center, double radius, double coefficient, AvoidanceType type) {
            this.center = center;
            this.radius = radius;
            this.coefficient = coefficient;
            this.type = type;
        }
    }
    
    /**
     * 避让类型枚举
     */
    public enum AvoidanceType {
        DANGER("危险区域"),
        MOB("怪物"),
        LAVA("熔岩"),
        WATER("水体"),
        FALL_RISK("跌落风险"),
        PLAYER("其他玩家"),
        CUSTOM("自定义");
        
        private final String description;
        
        AvoidanceType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 避让信息类
     */
    public static class AvoidanceInfo {
        public final AvoidanceType type;
        public final BetterBlockPos center;
        public final double radius;
        public final double coefficient;
        public final double distance;
        
        public AvoidanceInfo(AvoidanceType type, BetterBlockPos center, double radius, double coefficient, double distance) {
            this.type = type;
            this.center = center;
            this.radius = radius;
            this.coefficient = coefficient;
            this.distance = distance;
        }
        
        @Override
        public String toString() {
            return String.format("%s [距离: %.1f, 半径: %.1f, 系数: %.1f]", 
                               type.getDescription(), distance, radius, coefficient);
        }
    }
    
    /**
     * 避让系统统计信息
     */
    public static class AvoidanceStats {
        public final int totalZones;
        public final int[] typeCounts;
        
        public AvoidanceStats(int totalZones, int[] typeCounts) {
            this.totalZones = totalZones;
            this.typeCounts = typeCounts;
        }
        
        public void printStats() {
            logger.info("=== 避让系统统计 ===");
            logger.info("总避让区域数: " + totalZones);
            
            AvoidanceType[] types = AvoidanceType.values();
            for (int i = 0; i < types.length; i++) {
                if (typeCounts[i] > 0) {
                    logger.info(types[i].getDescription() + ": " + typeCounts[i] + "个");
                }
            }
        }
    }
}