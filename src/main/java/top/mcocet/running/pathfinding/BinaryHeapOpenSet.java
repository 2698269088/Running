/*
 * 本文件移植自 Baritone（LGPL-3.0）的 baritone.pathing.calc.openset.BinaryHeapOpenSet。
 */
package top.mcocet.running.pathfinding;

import java.util.Arrays;

/**
 * 以 {@code combinedCost} 为比较键的最小二叉堆 open set，移植自 Baritone（leijurv）。
 *
 * <p>{@code keys[i]} 恒等于 {@code array[i].combinedCost}，使得下沉/上浮时比较的是
 * 相邻的两个 double，而非逐层追逐指针，显著提升缓存友好度。索引自 1 开始，
 * {@code PathNode.heapPosition} 记录节点在堆中的下标以支持 decrease-key。</p>
 */
public final class BinaryHeapOpenSet {

    private static final int INITIAL_CAPACITY = 1024;

    private PathNode[] array;
    private double[] keys;
    private int size;

    public BinaryHeapOpenSet() {
        this(INITIAL_CAPACITY);
    }

    public BinaryHeapOpenSet(int capacity) {
        this.size = 0;
        this.array = new PathNode[capacity];
        this.keys = new double[capacity];
    }

    public int size() {
        return size;
    }

    public final void insert(PathNode value) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
            keys = Arrays.copyOf(keys, keys.length << 1);
        }
        size++;
        siftUp(size, value, value.combinedCost);
    }

    /** cost 只会下降，因此 combinedCost 只会变小，永远只需上浮 */
    public final void update(PathNode val) {
        siftUp(val.heapPosition, val, val.combinedCost);
    }

    private void siftUp(int index, PathNode val, double cost) {
        PathNode[] array = this.array;
        double[] keys = this.keys;
        int parentInd = index >>> 1;
        while (index > 1 && keys[parentInd] > cost) {
            PathNode parentNode = array[parentInd];
            array[index] = parentNode;
            keys[index] = keys[parentInd];
            parentNode.heapPosition = index;
            index = parentInd;
            parentInd = index >>> 1;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapPosition = index;
    }

    public final boolean isEmpty() {
        return size == 0;
    }

    public final PathNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("Cannot remove from empty heap");
        }
        PathNode[] array = this.array;
        double[] keys = this.keys;
        PathNode result = array[1];
        result.heapPosition = -1;
        PathNode val = array[size];
        double cost = keys[size];
        array[size] = null;
        size--;
        if (size == 0) {
            return result;
        }
        int index = 1;
        int child = 2;
        while (child <= size) {
            double childCost = keys[child];
            if (child < size && keys[child + 1] < childCost) {
                child++;
                childCost = keys[child];
            }
            if (cost <= childCost) {
                break;
            }
            PathNode childNode = array[child];
            array[index] = childNode;
            keys[index] = childCost;
            childNode.heapPosition = index;
            index = child;
            child = index << 1;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapPosition = index;
        return result;
    }
}
