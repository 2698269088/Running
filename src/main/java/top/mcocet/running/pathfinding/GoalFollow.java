package top.mcocet.running.pathfinding;

import top.mcocet.running.world.EntityTracker;
import top.mcocet.running.world.PlayerList;

import java.util.UUID;

/**
 * 跟随玩家的目标（{@code goto follow <name>}）：目标列随被跟随玩家的实时位置移动，
 * 到达时保持约 {@code keepRadius} 格的水平间距（切比雪夫距离），避免贴脸推挤。
 *
 * <p>位置解析链：用户名 →（{@link PlayerList}）UUID →（{@link EntityTracker}）当前实体快照。
 * 每次 {@link #isInGoal} / {@link #heuristic} 都重新读取最新位置；单次 A* 计算在数百毫秒内
 * 完成，玩家位移不足一格（落到同一方块列），故启发式在一次搜索内基本恒定，与缓存节点的
 * Baritone 语义一致。</p>
 *
 * <p>当目标玩家暂时不可见（区块外 / 已移除）时，回退到最近一次已知位置，使跟随在目标
 * 重新出现前保持朝最后所见方向推进。</p>
 *
 * <p>线程：{@link #isInGoal}/{@link #heuristic} 由寻路计算线程与引擎线程并发调用；
 * 对最近已知位置的缓存读写以本对象锁保护。</p>
 */
public final class GoalFollow implements Goal {

    private final String playerName;
    private final PlayerList playerList;
    private final EntityTracker tracker;
    private final int keepRadius;

    /** 最近一次解析到的目标方块列（缓存，供目标暂时不可见时回退） */
    private int lastTargetX;
    private int lastTargetZ;
    private boolean hasLast;

    public GoalFollow(String playerName, PlayerList playerList, EntityTracker tracker, int keepRadius) {
        this.playerName = playerName;
        this.playerList = playerList;
        this.tracker = tracker;
        this.keepRadius = keepRadius > 0 ? keepRadius : 2;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        int[] target = resolveTarget();
        if (target == null) {
            return false;
        }
        return Math.abs(x - target[0]) <= keepRadius && Math.abs(z - target[1]) <= keepRadius;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        int[] target = resolveTarget();
        if (target == null) {
            return 0;
        }
        // 目标收缩到保持半径边界：半径内视为已到达（启发式为 0），保持可采纳性
        int dx = shrink(x - target[0]);
        int dz = shrink(z - target[1]);
        return GoalXZ.calculate(dx, dz);
    }

    /**
     * 目标玩家当前是否已被加载跟踪（有实时实体快照）。用于命令层在发起跟随前确认
     * 目标可见——避免向未知坐标盲目寻路。
     */
    public boolean isTargetLoaded() {
        UUID uuid = playerList.findByName(playerName);
        return uuid != null && tracker.findByUuid(uuid) != null;
    }

    public String playerName() {
        return playerName;
    }

    /** 目标方块列（实时快照优先，否则最近已知）；从未见过目标返回 {@code null}。
     *  供 {@link PathingGoal} 直挖隧道解析锚点。 */
    public int[] targetColumn() {
        return resolveTarget();
    }

    @Override
    public String toString() {
        return "GoalFollow{player=" + playerName + ",keepRadius=" + keepRadius + "}";
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 单方向按保持半径收缩：|d| ≤ r 归零，否则减去 r 并保持符号。 */
    private int shrink(int d) {
        if (Math.abs(d) <= keepRadius) {
            return 0;
        }
        return d - Integer.signum(d) * keepRadius;
    }

    /**
     * 解析目标方块列：优先实时快照并刷新缓存；目标暂不可见时回退到最近已知位置；
     * 从未见过目标返回 {@code null}。
     */
    private synchronized int[] resolveTarget() {
        UUID uuid = playerList.findByName(playerName);
        EntityTracker.EntitySnapshot snapshot = uuid != null ? tracker.findByUuid(uuid) : null;
        if (snapshot != null) {
            lastTargetX = floor(snapshot.x());
            lastTargetZ = floor(snapshot.z());
            hasLast = true;
            return new int[]{lastTargetX, lastTargetZ};
        }
        if (hasLast) {
            return new int[]{lastTargetX, lastTargetZ};
        }
        return null;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
