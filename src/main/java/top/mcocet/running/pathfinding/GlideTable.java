package top.mcocet.running.pathfinding;

/**
 * 鞘翅滑翔弹道预计算表：按官方 {@code LivingEntity.updateFallFlyingMovement}
 * 与 {@code FireworkRocketEntity.tick} 附着分支逐 tick 迭代一条固定俯仰角的
 * 二维弹道（水平沿视线、垂直向下），产出"tick → (累计水平距离, 累计下落,
 * 水平/垂直速度, 已耗火箭数)"采样数组，供 {@link Moves} 的 GLIDE 移动做
 * 落点与代价查询。
 *
 * <p>与真实物理的差别（可接受的近似，代价建模用途）：忽略碰撞与方块速度因子、
 * 忽略重力中的缓降/漂浮药水分支（固定 0.08）、把烟花加速抽象为"水平速度低于
 * 阈值即挂一枚窗口为 {@code 10×(duration+1)} tick 的火箭"（与自动维持速度的
 * 实际执行策略一致）。全程垂直速度浅于 −0.5 时摔落距离被官方
 * {@code checkFallDistanceAccumulation} 封顶 1.0——着陆免伤，此时
 * {@link #profileSafe()} 为 true。</p>
 *
 * <p>纯静态计算、构建后只读，路径计算线程共享安全。</p>
 */
public final class GlideTable {

    /** 每个采样点的累计水平距离（格，沿视线水平方向） */
    public final double[] horizDist;
    /** 每个采样点的累计下落（格，正数） */
    public final double[] drop;
    /** 每个采样点时的水平速度（格/tick） */
    public final double[] speedH;
    /** 每个采样点时的垂直速度（格/tick，负值） */
    public final double[] speedY;
    /** 每个采样点时累计消耗的火箭数 */
    public final int[] rockets;
    /** 弹道是否全程处于"摔落距离封顶"安全区（垂直速度浅于 -0.5，着陆免伤） */
    private final boolean safeProfile;

    private GlideTable(double[] horizDist, double[] drop, double[] speedH,
                       double[] speedY, int[] rockets, boolean safeProfile) {
        this.horizDist = horizDist;
        this.drop = drop;
        this.speedH = speedH;
        this.speedY = speedY;
        this.rockets = rockets;
        this.safeProfile = safeProfile;
    }

    /** 着陆免伤（全程 |vy| < 0.5，摔落距离被封顶 1.0）。GLIDE 移动仅接受安全剖面。 */
    public boolean profileSafe() {
        return safeProfile;
    }

    public int samples() {
        return horizDist.length;
    }

    /**
     * 逐 tick 模拟官方滑翔弹道生成表。
     *
     * @param pitchDeg      巡航俯仰角（度；负值抬头）
     * @param launchSpeed   起跳出发的水平速度（冲刺跳约 0.6，步行跳约 0.4）
     * @param boostMinSpeed 自动补弹的水平速度阈值（格/tick）
     * @param rocketWindow  每枚火箭的加速窗口（tick，10×(duration+1)）
     * @param rocketBudget  火箭消耗上限（达到后不再补给）
     * @param maxTicks      模拟步数上限（对应最大滑翔距离截断）
     * @param allowBoost    false 时生成"无补给"剖面
     */
    public static GlideTable build(double pitchDeg, double launchSpeed, double boostMinSpeed,
                                   int rocketWindow, int rocketBudget, int maxTicks,
                                   boolean allowBoost) {
        double pitchRad = Math.toRadians(pitchDeg);
        double cosP = Math.cos(pitchRad);
        double sinP = Math.sin(pitchRad);
        // 视线单位向量（水平取 +x）：(cosP, -sinP)
        double lookX = cosP;
        double lookY = -sinP;
        double lookHor = Math.abs(lookX);

        double[] hd = new double[maxTicks + 1];
        double[] dp = new double[maxTicks + 1];
        double[] sh = new double[maxTicks + 1];
        double[] sy = new double[maxTicks + 1];
        int[] rk = new int[maxTicks + 1];

        double vx = launchSpeed;
        double vy = 0.0;
        int boostTicks = 0;
        int rocketsUsed = 0;
        double horiz = 0.0;
        double dropTotal = 0.0;
        boolean safe = true;
        int n = 0;
        hd[0] = 0;
        dp[0] = 0;
        sh[0] = vx;
        sy[0] = vy;
        rk[0] = 0;

        for (int t = 1; t <= maxTicks; t++) {
            // 与物理层 tick 顺序一致：先消费火箭加速窗口，再推进滑翔弹道
            if (boostTicks > 0) {
                vx += lookX * 0.1 + (lookX * 1.5 - vx) * 0.5;
                vy += lookY * 0.1 + (lookY * 1.5 - vy) * 0.5;
                boostTicks--;
            }
            double lift = cosP * cosP;
            vy += 0.08 * (-1.0 + lift * 0.75);
            double moveHor = Math.abs(vx);
            if (vy < 0.0 && lookHor > 0.0) {
                double convert = vy * -0.1 * lift;
                vx += lookX * convert / lookHor;
                vy += convert;
            }
            if (pitchRad < 0.0 && lookHor > 0.0) {
                double convert = moveHor * (-Math.sin(pitchRad)) * 0.04;
                vx += -lookX * convert / lookHor;
                vy += convert * 3.2;
            }
            if (lookHor > 0.0) {
                vx += (lookX / lookHor * moveHor - vx) * 0.1;
            }
            vx *= 0.99;
            vy *= 0.98;

            // 自动维持速度：速度跌破阈值且未超预算时挂一枚火箭（下一 tick 起生效）
            if (allowBoost && boostTicks == 0 && rocketsUsed < rocketBudget
                    && Math.abs(vx) < boostMinSpeed) {
                boostTicks = rocketWindow;
                rocketsUsed++;
            }

            horiz += vx;
            dropTotal -= vy; // vy 负值为下落
            if (vy < -0.5) {
                safe = false; // 超出官方摔落距离封顶区，着陆可能受伤
            }
            n = t;
            hd[t] = horiz;
            dp[t] = Math.max(0.0, dropTotal);
            sh[t] = vx;
            sy[t] = vy;
            rk[t] = rocketsUsed;
        }
        return new GlideTable(hd, dp, sh, sy, rk, safe);
    }

    /**
     * 在弹道上按水平距离插值求采样点：返回 {下落量, 当前水平速度, 当前垂直速度,
     * 截至该点的火箭数(取整), 已用 tick 数}；水平距离超出表尾返回 {@code null}
     * （调用方按最大跨度截断）。
     */
    public double[] sampleAtHoriz(double targetHoriz) {
        if (targetHoriz > horizDist[n()]) {
            return null;
        }
        int lo = 0;
        int hi = n();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (horizDist[mid] < targetHoriz) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int i1 = lo;
        int i0 = Math.max(0, lo - 1);
        double span = horizDist[i1] - horizDist[i0];
        double f = span > 1.0E-9 ? (targetHoriz - horizDist[i0]) / span : 0.0;
        return new double[]{
                drop[i0] + (drop[i1] - drop[i0]) * f,
                speedH[i0] + (speedH[i1] - speedH[i0]) * f,
                speedY[i0] + (speedY[i1] - speedY[i0]) * f,
                rockets[i1],
                i1};
    }

    /** 表的有效末点索引（距离单调不减，落后即止）。 */
    public int n() {
        int last = horizDist.length - 1;
        while (last > 0 && Double.isNaN(horizDist[last])) {
            last--;
        }
        return last;
    }

    /** 弹道总水平距离（格）。 */
    public double totalHoriz() {
        return horizDist[n()];
    }
}
