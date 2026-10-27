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

    /** 梯子/藤蔓向上爬一格 */
    public static final double LADDER_UP_ONE_COST = WALK_ONE_BLOCK_COST * 1.5;
    /** 梯子/藤蔓向下爬一格 */
    public static final double LADDER_DOWN_ONE_COST = WALK_ONE_BLOCK_COST * 1.2;
    /** 放置一个方块的代价（含切换物品与点击时间） */
    public static final double PLACE_ONE_BLOCK_COST = 2.0;
    /**
     * 发射一枚烟花火箭的额外代价（选弹/换持/等待消耗确认，单位≈tick）；
     * 滑翔弹道自身耗时由 {@link GlideTable} 逐 tick 累计计入。
     */
    public static final double ROCKET_ONE_COST = 5.0;

    /**
     * 灵魂沙上走一格（对齐 Baritone {@code WALK_ONE_OVER_SOUL_SAND_COST}）：
     * 原版 {@code speedFactor=0.4}，故耗时为平地的 {@code 1/0.4} 倍。
     */
    public static final double WALK_ONE_OVER_SOUL_SAND_COST = WALK_ONE_BLOCK_COST / 0.4; // 11.583

    /**
     * 不可通行的哨兵代价。刻意不用 {@link Double#MAX_VALUE}，因为它会与其它代价或
     * 彼此相加时溢出为负数。
     */
    public static final double COST_INF = 1000000;

    /** 启发式乘子（对应 Baritone {@code costHeuristic}，默认 1.0 以保证可采纳性） */
    public static final double COST_HEURISTIC = 1.0;

    /**
     * “挖掘优先”模式下挖块耗时的代价乘子（&lt;1）：把真实挖掘 tick 数打折计入 A* 代价，
     * 使寻路倾向直挖穿墙而非绕路（对齐 Baritone 调低 {@code mineBlockMultiplier} 的思路）。
     * 打折仅作用于挖块附加项，基础走格代价不变，故启发式仍可采纳（单步最低代价不变）。
     */
    public static final double DIG_PRIORITY_BREAK_DISCOUNT = 0.3;

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
     * Parkour 跳跨 {@code dist} 格（含起跳格）的水平代价，对齐 Baritone
     * {@code MovementParkour.costFromJumpDistance}：2/3 格按走速、4 格（仅冲刺）按跑速。
     *
     * @param dist 跳跃水平跨度（2..4）
     * @return 该跨度的移动耗时（单位≈tick）
     */
    public static double parkourCost(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2;
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            default:
                throw new IllegalArgumentException("非法的 parkour 跨度: " + dist);
        }
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
