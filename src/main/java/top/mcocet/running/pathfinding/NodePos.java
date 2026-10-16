/*
 * 本文件的 longHash 移植自 Baritone（LGPL-3.0）的 baritone.api.utils.BetterBlockPos#longHash。
 */
package top.mcocet.running.pathfinding;

import java.util.Objects;

/**
 * 不可变的整数方块坐标（寻路节点位置），替代 Baritone 依赖 {@code net.minecraft.core.BlockPos}
 * 的 {@code BetterBlockPos}。仅保留寻路需要的字段与哈希逻辑。
 */
public final class NodePos {

    public final int x;
    public final int y;
    public final int z;

    public NodePos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public NodePos above() {
        return new NodePos(x, y + 1, z);
    }

    public NodePos below() {
        return new NodePos(x, y - 1, z);
    }

    /**
     * 坐标三元组的 long 哈希，移植自 {@code BetterBlockPos.longHash}。
     *
     * <p>刻意不用 MC {@code Vec3i} 原本 {@code (y + z*31)*31 + x} 的实现（碰撞极多），
     * 而是自行用大素数混合 x/y/z。</p>
     */
    public static long longHash(int x, int y, int z) {
        long hash = 3241;
        hash = 3457689L * hash + x;
        hash = 8734625L * hash + y;
        hash = 2873465L * hash + z;
        return hash;
    }

    public long hash() {
        return longHash(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodePos)) {
            return false;
        }
        NodePos other = (NodePos) o;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "NodePos{x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
