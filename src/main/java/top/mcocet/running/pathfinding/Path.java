package top.mcocet.running.pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计算完成的路径：由 {@link PathNode} 前驱链重建为有序步骤序列。
 *
 * <p>每一步记录使用的 {@link Moves}、起点与终点节点坐标，供 {@link PathingGoal} 逐步执行。
 * 对应 Baritone 的 {@code Path}，但省去 IPath 的 MC 相关后处理（splice / cutoff 等）。</p>
 */
public final class Path {

    /** 路径中的一步：从 {@link #src} 经 {@link #move} 到达 {@link #dest}（均为脚部节点坐标） */
    public static final class Step {
        public final Moves move;
        public final NodePos src;
        public final NodePos dest;

        Step(Moves move, NodePos src, NodePos dest) {
            this.move = move;
            this.src = src;
            this.dest = dest;
        }
    }

    private final NodePos start;
    private final NodePos end;
    private final List<Step> steps;

    private Path(NodePos start, NodePos end, List<Step> steps) {
        this.start = start;
        this.end = end;
        this.steps = steps;
    }

    /**
     * 从终点节点沿 {@code previous} 链回溯重建路径。链头为起点（{@code previous==null}）。
     *
     * @param endNode 搜索到达的终点节点
     * @return 重建的路径；{@code endNode} 无有效链时返回空步骤路径
     */
    public static Path fromNodeChain(PathNode endNode) {
        List<PathNode> chain = new ArrayList<>();
        for (PathNode n = endNode; n != null; n = n.previous) {
            chain.add(n);
        }
        Collections.reverse(chain);
        List<Step> steps = new ArrayList<>(Math.max(0, chain.size() - 1));
        for (int i = 1; i < chain.size(); i++) {
            PathNode prev = chain.get(i - 1);
            PathNode cur = chain.get(i);
            Moves move = cur.move;
            steps.add(new Step(move, new NodePos(prev.x, prev.y, prev.z), new NodePos(cur.x, cur.y, cur.z)));
        }
        NodePos start = chain.isEmpty() ? null : new NodePos(chain.get(0).x, chain.get(0).y, chain.get(0).z);
        NodePos end = new NodePos(endNode.x, endNode.y, endNode.z);
        return new Path(start, end, steps);
    }

    public NodePos getStart() {
        return start;
    }

    public NodePos getEnd() {
        return end;
    }

    public List<Step> steps() {
        return steps;
    }

    public int length() {
        return steps.size();
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    @Override
    public String toString() {
        return "Path{" + start + " -> " + end + ", steps=" + steps.size() + "}";
    }
}
