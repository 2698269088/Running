package top.mcocet.running.flight;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞行决策选择器（思路完整移植 MovementSync 1.11 的 {@code VanillaGlideMode}
 * 候选网格 + {@code FlightSelector} 分级排序）：每 tick 对
 * {@code 8 个航向偏移 × 5 档俯仰} 的候选动作做<b>纯物理向前推演</b>，用伤害分级
 * 与目标推进代价选出一个最优提案；选择过程绝不执行控制、不消耗火箭、不改变状态。
 *
 * <p>安全分级（对齐 MS 的 {@code DamageClass}，从优到劣排序）：</p>
 * <ul>
 *   <li>{@link DamageClass#SAFE}——推演终点无伤害（含浅落封顶免伤着陆）；</li>
 *   <li>{@link DamageClass#DAMAGING}——有正伤害但低于当前血量；</li>
 *   <li>{@link DamageClass#LETHAL}——减免后伤害 ≥ 当前血量（致命，永不主动选）；</li>
 *   <li>{@link DamageClass#UNKNOWN}——弹道掠过未加载区块或推演终止于未知——
 *       <b>永不接受</b>（MS："never approve blind low flight"）。</li>
 * </ul>
 *
 * <p>常规巡航只在 SAFE 中选"代价最低"（对目标推进最大、耗时最短）；
 * {@code emergency}（迫降/低血/耐久将尽）改为伤害分级优先、代价次之；
 * 无任何可接受候选时 {@link #decide} 返回 {@code null}，调用方应转向
 * {@link #forceLand}（最小伤害着陆）或 {@link #emergencyHold}（抬头保高度等待）。</p>
 *
 * <p>相对 MS 的移植取舍：recovery（落点二次抬头推演）合入候选网格自身
 * （-55/-30 抬头档即可覆盖"落地前拉起"）；火箭候选仅为正前方航向生成
 * （点火时刻改航向无物理依据，也控制每 tick 推演规模）。</p>
 */
public final class FlightSelector {

    /** 候选航向偏移（度，对齐 MS YAW_OFFSETS） */
    private static final float[] YAW_OFFSETS = {0, -30, 30, -60, 60, -100, 100, 180};
    /** 候选俯仰档（度，对齐 MS PITCHES：-55/−30 拉起、-10/0 巡航、+10 俯冲增速） */
    private static final float[] PITCHES = {-55, -30, -10, 0, 10};
    /** 单条弹道向前推演的 tick 上限（≈2 秒） */
    private static final int MAX_TICKS = 40;
    /** 一枚火箭的决策代价（≈换持+等待确认耗时，与 MoveCosts.ROCKET_ONE_COST 同量级） */
    private static final double ROCKET_COST = 6.0;
    /** 高度维持参考的"前方虚拟目标"距离（无真实目标时巡航锚点） */
    public static final double CRUISE_LOOKAHEAD = 64.0;

    /** 伤害分级，从优到劣（枚举序即排序序） */
    public enum DamageClass {
        /** 推演终点无伤害 */
        SAFE,
        /** 正伤害但低于当前血量 */
        DAMAGING,
        /** 伤害达到当前血量（致命） */
        LETHAL,
        /** 未知（未加载/未定），永不接受 */
        UNKNOWN
    }

    /** 安全评估：分级 + 累计伤害（减免前；本插件无盔甲建模，减免后按同值保守计）。 */
    public record Safety(DamageClass damageClass, double raw) implements Comparable<Safety> {
        @Override
        public int compareTo(Safety other) {
            int rank = damageClass.compareTo(other.damageClass);
            return rank == 0 ? Double.compare(raw, other.raw) : rank;
        }
    }

    /** 选出的提案（未被执行；调用方负责限速转向与火箭发射）。 */
    public record Decision(FlightAction action, Safety safety, Trajectory trajectory) {
    }

    /**
     * 对上下文做一次候选评估。
     *
     * @return 最优可接受提案；全部 UNKNOWN/LETHAL 时返回 {@code null}
     *         （调用方转迫降或保高）
     */
    public Decision decide(FlightContext ctx) {
        return select(ctx, headingToTarget(ctx), false);
    }

    /**
     * 迫降决策：目标视为"脚下的安全落点"，damage-first 排序选最小伤害候选；
     * 全部不可推演时退化为抬头缓降（争取时间等区块加载/降高）。
     */
    public Decision forceLand(FlightContext ctx) {
        Decision d = select(ctx, ctx.yaw(), true);
        if (d != null) {
            return d;
        }
        // 保高应急：抬头 -30、不点火，等待下一 tick 重估
        FlightAction hold = new FlightAction(ctx.yaw(), -30.0F, false);
        return new Decision(hold, new Safety(DamageClass.UNKNOWN, 0), null);
    }

    /** 当前姿态不做候选时，抬头保高度等救援（对齐 MS emergencyHold）。 */
    public FlightAction emergencyHold(FlightContext ctx) {
        return new FlightAction(ctx.yaw(), -30.0F, false);
    }

    // ------------------------------------------------------------------
    // 内部：候选生成、推演与排序
    // ------------------------------------------------------------------

    private Decision select(FlightContext ctx, float heading, boolean damageFirst) {
        boolean rocketAllowed = !ctx.attached() && ctx.fireworks() > 0;
        List<Trajectory> trajs = new ArrayList<>(PITCHES.length * (YAW_OFFSETS.length + 1));
        List<FlightAction> actions = new ArrayList<>(trajs.size());
        for (float offset : YAW_OFFSETS) {
            float yaw = wrapDegrees(heading + offset);
            for (float pitch : PITCHES) {
                FlightAction a = new FlightAction(yaw, pitch, false);
                trajs.add(predictFor(ctx, a));
                actions.add(a);
            }
        }
        if (rocketAllowed) {
            // 火箭候选：沿当前目标航向正前（点火不用于转向，转向交给普通候选）
            for (float pitch : PITCHES) {
                FlightAction a = new FlightAction(heading, pitch, true);
                trajs.add(predictFor(ctx, a));
                actions.add(a);
            }
        }

        Decision best = null;
        double bestCost = Double.NaN;
        for (int i = 0; i < trajs.size(); i++) {
            Trajectory traj = trajs.get(i);
            if (traj.outcome == Trajectory.Outcome.UNKNOWN) {
                continue; // 未知永不接受
            }
            Safety safety = classify(ctx, traj);
            if (safety.damageClass() == DamageClass.LETHAL) {
                continue; // 致命永不主动选
            }
            if (!damageFirst && safety.damageClass() != DamageClass.SAFE) {
                continue; // 常规巡航只取安全候选
            }
            double cost = cost(ctx, actions.get(i), traj, safety);
            if (best == null) {
                best = new Decision(actions.get(i), safety, traj);
                bestCost = cost;
                continue;
            }
            // damage-first：先比安全再比代价；常规：先比代价、平级再比安全
            int cmp = damageFirst
                    ? safety.compareTo(best.safety())
                    : Double.compare(cost, bestCost);
            if (cmp < 0 || (cmp == 0 && damageFirst && cost < bestCost)) {
                best = new Decision(actions.get(i), safety, traj);
                bestCost = cost;
            }
        }
        return best;
    }

    private Trajectory predictFor(FlightContext ctx, FlightAction action) {
        return Trajectory.predict(ctx.query(),
                ctx.x(), ctx.y(), ctx.z(), ctx.vx(), ctx.vy(), ctx.vz(), ctx.fallDist(),
                action.yaw(), action.pitch(), action.firework(),
                gravity(ctx), ctx.worldMinY(), MAX_TICKS);
    }

    /** 有效重力：缓降药水的 min 处理由物理层权威维护，此处取基准常量保持一致。 */
    private static double gravity(FlightContext ctx) {
        return top.mcocet.running.bot.PlayerPhysics.GRAVITY;
    }

    /** 把推演结果分类为 Safety（对齐 MS classify）。 */
    private Safety classify(FlightContext ctx, Trajectory traj) {
        if (traj.outcome == Trajectory.Outcome.UNKNOWN) {
            return new Safety(DamageClass.UNKNOWN, 0);
        }
        double damage = traj.totalDamage();
        if (traj.outcome == Trajectory.Outcome.HAZARD) {
            return new Safety(DamageClass.LETHAL, damage);
        }
        if (damage <= 0.0) {
            return new Safety(DamageClass.SAFE, 0);
        }
        return new Safety(damage >= ctx.health() ? DamageClass.LETHAL : DamageClass.DAMAGING,
                damage);
    }

    /**
     * 候选代价：tick 耗时 + 未到位惩罚 + 中断惩罚 + 火箭成本 + 姿态偏好 ± 伤害
     * ± <b>高度价值</b>。末端（或落点）水平距目标 &lt; radius 且高度接近 → 完成捷径
     * （大幅减价）。
     *
     * <p>高度价值项是"能爬升"的关键：原版火箭推力严格沿视线，只有选上仰档
     * （pitch 负）+点火才产生上升——若只评水平推进，决策永远选平飞/俯冲，
     * 表现为"烟花激活但不升高度"。低于期望高度罚 1.5/格（重）、高于罚 0.5/格
     * （轻）：不对称惩罚使弹道在欠高时选"抬头+火箭"候选、过高时自然俯冲，
     * 复现原版锯齿式爬升巡航。</p>
     */
    private double cost(FlightContext ctx, FlightAction action, Trajectory traj, Safety safety) {
        double endX = traj.outcome == Trajectory.Outcome.LANDED ? traj.landX : traj.endX;
        double endZ = traj.outcome == Trajectory.Outcome.LANDED ? traj.landZ : traj.endZ;
        double endY = traj.outcome == Trajectory.Outcome.LANDED ? traj.landY : traj.endY;
        double endDist = Math.hypot(ctx.targetX() - endX, ctx.targetZ() - endZ);
        boolean arrived = endDist <= ctx.radius()
                && Math.abs(endY - ctx.targetY()) <= 3.0;

        double cost = traj.ticks;
        // 对目标的推进为主收益（每格推进记 2 倍权重，鼓励"飞得远"）
        cost -= 2.0 * (ctx.distanceToTarget() - endDist);
        // 高度价值（不对称）：低于期望高度重罚→选上仰/爬坡候选，高于轻罚→允许慢降
        double dyToTarget = endY - ctx.targetY();
        cost += dyToTarget >= 0.0 ? dyToTarget * 0.5 : -dyToTarget * 1.5;
        if (arrived) {
            cost -= 60.0;
        }
        switch (traj.outcome) {
            case LANDED -> cost += 40.0 + 2.0 * endDist; // 提前落地：飞行中断，按剩余距离罚
            case BLOCKED -> cost += 30.0 + 2.0 * endDist; // 撞壁停挂
            case WATER -> cost += 35.0 + 2.0 * endDist;   // 落水停
            default -> { }
        }
        if (action.firework()) {
            cost += ROCKET_COST;
        }
        // 姿态偏好：远离当前姿态的候选罚（防每 tick 候选抖动改航向/俯仰打架）
        cost += Math.abs(wrapDegrees(action.yaw() - ctx.yaw())) * 0.05
                + Math.abs(action.pitch() - ctx.pitch()) * 0.2;
        // 伤害项：分级已排序，这里只作为同分级内的细调
        cost += safety.raw() * 5.0;
        return cost;
    }

    /** 当前指向目标的水平方位角（无水平距离时保持当前 yaw）。 */
    private float headingToTarget(FlightContext ctx) {
        double dx = ctx.targetX() - ctx.x();
        double dz = ctx.targetZ() - ctx.z();
        if (dx * dx + dz * dz < 1.0E-4) {
            return ctx.yaw();
        }
        // MC 朝向水平分量 = (-sin(yaw), cos(yaw))
        return wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** 角度收拢到 (-180, 180]。 */
    private static float wrapDegrees(float degrees) {
        float value = degrees % 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }
}
