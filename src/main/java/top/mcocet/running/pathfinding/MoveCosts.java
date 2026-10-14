/*
 * 本文件移植自 Baritone（cabaletta/baritone, LGPL-3.0）的
 * baritone.api.pathing.movement.ActionCosts，并按 Running 的无头移动底座做了裁剪。
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package top.mcocet.running.pathfinding;

/**
 * 寻路代价常量，移植自 Baritone 的 {@code ActionCosts}。
 *
 * <p>代价以 tick（约 1/20 秒）为大致单位：走一格平地约 {@link #WALK_ONE_BLOCK_COST} tick，
 * 冲刺更快（乘 {@link #SPRINT_MULTIPLIER}），坠落代价由 {@link #distanceToTicks} 依自由落体
 * 速度递推得到。首版仅使用基础移动所需常量，破块 / 放置 / 梯子 / 流体等代价占位保留。</p>
 */
public final class MoveCosts {

    private MoveCosts() {
    }

    /** 走一格平地（约 4.317 格/秒）耗时，单位≈tick */
    public static final double WALK_ONE_BLOCK_COST = 20 / 4.317; // 4.633
    /** 疾跑走一格（约 5.612 格/秒） */
    public static final double SPRINT_ONE_BLOCK_COST = 20 / 5.612; // 3.564
    /** 冲刺对走格代价的乘子（<1，越小越快） */
    public static final double SPRINT_MULTIPLIER = SPRINT_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST; // 0.769
    /** 潜行走一格 */
    public static final double SNEAK_ONE_BLOCK_COST = 20 / 1.3; // 15.385
    /** 走下边缘：先走 0.5 到边缘再走 0.3 开始下落 */
    public static final double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8; // 3.706
    /** 落到新格后走满居中所需代价 */
    public static final double CENTER_AFTER_FALL_COST = WALK_ONE_BLOCK_COST - WALK_OFF_BLOCK_COST; // 0.927

    /**
     * 不可通行的哨兵代价。刻意不用 {@link Double#MAX_VALUE}，因为它会与其它代价或
     * 彼此相加时溢出为负数。
     */
    public static final double COST_INF = 1000000;

    /** 启发式乘子（对应 Baritone {@code costHeuristic}，默认 1.0 以保证可采纳性） */
    public static final double COST_HEURISTIC = 1.0;

    public static final double FALL_1_25_BLOCKS_COST = distanceToTicks(1.25);
    public static final double FALL_0_25_BLOCKS_COST = distanceToTicks(0.25);
    /**
     * 上一格代价：跳跃获得 1.25 格上抛速度，落地时剩 0.25 格的回落对称，
     * 因此上跳净耗时 = 落 1.25 - 落 0.25（移植自 Baritone 的推导）。
     */
    public static final double JUMP_ONE_BLOCK_COST = FALL_1_25_BLOCKS_COST - FALL_0_25_BLOCKS_COST;

    /** 落 N 格的代价表（下标即方块数，覆盖 0..4096） */
    public static final double[] FALL_N_BLOCKS_COST = generateFallNBlocksCost();

    /**
     * 破坏一个方块所需 tick 数（忠实移植原版 {@code Player.getDestroyProgress}）。
     *
     * <p>原版每 tick 累积的破坏进度：{@code damagePerTick = digSpeed / hardness /
     * (hasCorrectToolForDrops ? 30 : 100)}，进度累到 1 即破。因此破块耗时为
     * {@code ceil(1 / damagePerTick)}。本方法不处理创造模式（瞬破）与附魔效率
     * / 疲劳效果，那些留作后续。</p>
     *
     * @param hardness            方块硬度（{@code <0} 或 {@code NaN} 视为不可破坏）
     * @param digSpeed            当前工具对该方块的有效挖掘速度（徒手为 1.0）
     * @param correctToolForDrops 是否持有可掉落该方块的正确工具（决定 30 / 100 除数）
     * @return 破块所需 tick；不可破坏返回 {@link #COST_INF}
     */
    public static double timeToBreak(double hardness, double digSpeed, boolean correctToolForDrops) {
        if (Double.isNaN(hardness) || hardness < 0) {
            return COST_INF; // 未知或不可破坏（基岩、末地传送门等）
        }
        if (hardness == 0) {
            return 1; // 硬度 0（草/雪片等）近似瞬破，至少占一个 tick
        }
        double divisor = correctToolForDrops ? 30 : 100;
        double perTick = digSpeed / hardness / divisor;
        if (perTick <= 0 || Double.isNaN(perTick)) {
            return COST_INF;
        }
        return Math.ceil(1.0 / perTick);
    }

    private static double[] generateFallNBlocksCost() {
        double[] costs = new double[4097];
        for (int i = 0; i < 4097; i++) {
            costs[i] = distanceToTicks(i);
        }
        return costs;
    }

    /** 第 ticks 刻末的下落速度（对齐 MC 空气阻力递推，移植自 Baritone） */
    private static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    /** 给定下落距离，反推需要多少 tick（自由落体逐 tick 累减） */
    public static double distanceToTicks(double distance) {
        if (distance == 0) {
            return 0; // 避免 0/0 NaN
        }
        double tmpDistance = distance;
        int tickCount = 0;
        while (true) {
            double fallDistance = velocity(tickCount);
            if (tmpDistance <= fallDistance) {
                return tickCount + tmpDistance / fallDistance;
            }
            tmpDistance -= fallDistance;
            tickCount++;
        }
    }
}
