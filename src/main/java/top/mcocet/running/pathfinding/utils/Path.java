package top.mcocet.running.pathfinding.utils;

import lombok.Getter;
import org.joml.Vector3d;
import top.mcocet.running.pathfinding.core.PathNode;

import java.util.List;

@Getter
public class Path {
    private final Vector3d startPos;
    private final List<PathNode> nodes;
    private final double totalCost;
    
    public Path(Vector3d startPos, List<PathNode> nodes) {
        this.startPos = startPos;
        this.nodes = nodes;
        this.totalCost = nodes.isEmpty() ? 0 : nodes.get(nodes.size() - 1).getCost();
    }
    
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
    
    public int size() {
        return nodes.size();
    }
    
    public PathNode getFirst() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }
    
    public PathNode getLast() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }
    
    public PathNode getNode(int index) {
        return nodes.get(index);
    }
    
    public Vector3d getStartPos() {
        return startPos;
    }
    
    @Override
    public String toString() {
        return String.format("Path{nodes=%d, cost=%.2f, from=%s to=%s}", 
            nodes.size(), totalCost, startPos, 
            nodes.isEmpty() ? "null" : nodes.get(nodes.size() - 1).getPosition());
    }
}