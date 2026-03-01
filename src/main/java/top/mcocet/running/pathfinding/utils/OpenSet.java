package top.mcocet.running.pathfinding.utils;

import top.mcocet.running.pathfinding.core.PathNode;

import java.util.*;

public class OpenSet {
    private final PriorityQueue<PathNode> heap;
    private final Set<PathNode> inHeap;
    
    public OpenSet() {
        this.heap = new PriorityQueue<>(Comparator.comparingDouble(PathNode::getCombinedCost));
        this.inHeap = new HashSet<>();
    }
    
    public void insert(PathNode node) {
        if (inHeap.add(node)) {
            heap.offer(node);
        }
    }
    
    public void update(PathNode node) {
        // PriorityQueue doesn't support efficient decrease-key operation
        // So we remove and reinsert
        if (inHeap.remove(node)) {
            heap.remove(node);
        }
        insert(node);
    }
    
    public PathNode removeLowest() {
        PathNode node = heap.poll();
        if (node != null) {
            inHeap.remove(node);
        }
        return node;
    }
    
    public boolean isEmpty() {
        return heap.isEmpty();
    }
    
    public int size() {
        return heap.size();
    }
}