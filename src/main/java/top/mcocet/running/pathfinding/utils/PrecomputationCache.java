package top.mcocet.running.pathfinding.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.pathfinding.core.PathNode;
import top.mcocet.running.utils.BetterBlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预计算缓存系统 - 借鉴Baritone的预计算设计理念
 * 缓存常用的计算结果以提升路径规划性能
 */
public class PrecomputationCache {
    private static final Logger logger = LoggerFactory.getLogger("RunningPCSystem");

    // 移动性缓存：存储位置是否可通行
    private final ConcurrentHashMap<Long, Boolean> walkabilityCache;
    
    // 成本缓存：存储移动成本
    private final ConcurrentHashMap<Long, Double> moveCostCache;
    
    // 启发式缓存：存储到目标的估计成本
    private final ConcurrentHashMap<Long, Double> heuristicCache;
    
    // 统计信息
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    public PrecomputationCache() {
        this.walkabilityCache = new ConcurrentHashMap<>();
        this.moveCostCache = new ConcurrentHashMap<>();
        this.heuristicCache = new ConcurrentHashMap<>();
    }
    
    /**
     * 缓存可行走性检查结果
     */
    public void cacheWalkability(BetterBlockPos pos, boolean walkable) {
        walkabilityCache.put(pos.longHash(), walkable);
    }
    
    /**
     * 获取缓存的可行走性检查结果
     */
    public Boolean getCachedWalkability(BetterBlockPos pos) {
        Long key = pos.longHash();
        if (walkabilityCache.containsKey(key)) {
            cacheHits.incrementAndGet();
            return walkabilityCache.get(key);
        }
        cacheMisses.incrementAndGet();
        return null;
    }
    
    /**
     * 缓存移动成本计算结果
     */
    public void cacheMoveCost(PathNode from, PathNode to, double cost) {
        long key = generateMoveKey(from, to);
        moveCostCache.put(key, cost);
    }
    
    /**
     * 获取缓存的移动成本
     */
    public Double getCachedMoveCost(PathNode from, PathNode to) {
        long key = generateMoveKey(from, to);
        if (moveCostCache.containsKey(key)) {
            cacheHits.incrementAndGet();
            return moveCostCache.get(key);
        }
        cacheMisses.incrementAndGet();
        return null;
    }
    
    /**
     * 缓存启发式估计值
     */
    public void cacheHeuristic(BetterBlockPos pos, double heuristic) {
        heuristicCache.put(pos.longHash(), heuristic);
    }
    
    /**
     * 获取缓存的启发式估计值
     */
    public Double getCachedHeuristic(BetterBlockPos pos) {
        Long key = pos.longHash();
        if (heuristicCache.containsKey(key)) {
            cacheHits.incrementAndGet();
            return heuristicCache.get(key);
        }
        cacheMisses.incrementAndGet();
        return null;
    }
    
    /**
     * 生成两个节点间移动的缓存键
     */
    private long generateMoveKey(PathNode from, PathNode to) {
        // 使用两个位置的哈希值组合生成唯一键
        long fromHash = from.getBetterPos().longHash();
        long toHash = to.getBetterPos().longHash();
        return fromHash ^ (toHash << 16) ^ (toHash >> 16);
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanupOldEntries(int maxEntries) {
        cleanupCache(walkabilityCache, maxEntries);
        cleanupCache(moveCostCache, maxEntries);
        cleanupCache(heuristicCache, maxEntries);
    }
    
    /**
     * 清理指定缓存中多余的条目
     */
    private <T> void cleanupCache(ConcurrentHashMap<Long, T> cache, int maxEntries) {
        if (cache.size() > maxEntries) {
            // 简单的LRU清理：移除一部分旧条目
            int removeCount = cache.size() - maxEntries / 2;
            cache.entrySet().stream()
                .limit(removeCount)
                .forEach(entry -> cache.remove(entry.getKey()));
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        long totalRequests = cacheHits.get() + cacheMisses.get();
        double hitRate = totalRequests > 0 ? (double) cacheHits.get() / totalRequests : 0.0;
        
        return new CacheStats(
            walkabilityCache.size(),
            moveCostCache.size(),
            heuristicCache.size(),
            cacheHits.get(),
            cacheMisses.get(),
            hitRate
        );
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAll() {
        walkabilityCache.clear();
        moveCostCache.clear();
        heuristicCache.clear();
        resetStats();
        logger.debug("预计算缓存已清空");
    }
    
    /**
     * 缓存统计信息类
     */
    public static class CacheStats {
        public final int walkabilityCacheSize;
        public final int moveCostCacheSize;
        public final int heuristicCacheSize;
        public final long hits;
        public final long misses;
        public final double hitRate;
        
        public CacheStats(int walkabilitySize, int moveCostSize, int heuristicSize, 
                         long hits, long misses, double hitRate) {
            this.walkabilityCacheSize = walkabilitySize;
            this.moveCostCacheSize = moveCostSize;
            this.heuristicCacheSize = heuristicSize;
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
        }
        
        public void printStats() {
            logger.info("=== 预计算缓存统计 ===");
            logger.info("可行走性缓存大小: " + walkabilityCacheSize);
            logger.info("移动成本缓存大小: " + moveCostCacheSize);
            logger.info("启发式缓存大小: " + heuristicCacheSize);
            logger.info("缓存命中次数: " + hits);
            logger.info("缓存未命中次数: " + misses);
            logger.info("缓存命中率: " + String.format("%.2f%%", hitRate * 100));
        }
    }
    
    /**
     * 预热常用位置的缓存
     */
    public void warmupCache(BetterBlockPos center, int radius) {
        logger.info("预热缓存区域: (" + center.x + ", " + center.y + ", " + center.z + ") 半径: " + radius);
        
        int warmedUp = 0;
        int skipped = 0;
        
        // 只预热一个小的核心区域，而不是整个球形区域
        int effectiveRadius = Math.min(radius, 5); // 限制在5格以内
        
        for (int x = center.x - effectiveRadius; x <= center.x + effectiveRadius; x++) {
            for (int y = Math.max(0, center.y - effectiveRadius); y <= Math.min(255, center.y + effectiveRadius); y++) {
                for (int z = center.z - effectiveRadius; z <= center.z + effectiveRadius; z++) {
                    BetterBlockPos pos = new BetterBlockPos(x, y, z);
                    
                    // 预计算基本的可行走性
                    boolean walkable = y >= 0 && y <= 255; // 简化的检查
                    cacheWalkability(pos, walkable);
                    
                    warmedUp++;
                }
            }
        }

        logger.info("预热完成，共缓存 " + warmedUp + " 个位置，跳过 " + skipped + " 个位置");
    }
}