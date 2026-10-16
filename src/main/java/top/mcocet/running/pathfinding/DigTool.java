package top.mcocet.running.pathfinding;

/**
 * 一次“挖掘某格”的工具规划结果：由 {@link PathWorld#planDigTool(int, int, int)} 产出，
 * 供 {@link PathingGoal} 决定“用哪件工具、是否需要先从背包把它取到快捷栏、按住多久”。
 *
 * <p>与仅扫描快捷栏的旧逻辑不同，本规划考虑<b>整个背包（0..35）</b>，因此当最合适的工具
 * 躺在主背包时，执行器会先把它换到某个快捷栏槽再开挖——这正是“卡住时自动从背包拿出合适
 * 工具挖掘前进”的依据。</p>
 *
 * @param ticks            以所选工具破坏该格所需 tick（徒手基线已纳入比较）；不可破坏为
 *                         {@link MoveCosts#COST_INF}
 * @param equipHotbarSlot  挖掘时应手持的快捷栏槽（0..8）；{@code -1} 表示无需改变手持
 *                         （徒手最快，或所选工具已在当前手持槽）
 * @param relocateFromSlot 当所选工具当前位于主背包（9..35）而需先换到 {@code equipHotbarSlot}
 *                         时，此处为源槽；否则 {@code -1}（无需搬迁）
 * @param breakable        该格是否可被破坏（区块已加载、非空气/流体、可挖且非不可破）
 */
public record DigTool(double ticks, int equipHotbarSlot, int relocateFromSlot, boolean breakable) {

    /** 不可破坏（或信息缺失）的规划结果。 */
    public static DigTool unbreakable() {
        return new DigTool(MoveCosts.COST_INF, -1, -1, false);
    }

    /** 是否需要先把工具从主背包搬到快捷栏。 */
    public boolean needsRelocate() {
        return breakable && relocateFromSlot >= 9;
    }
}
