package top.mcocet.running.pathfinding;

/**
 * 一次“放置方块搭桥”的规划结果：由 {@link PathWorld#planPlaceBlock()} 产出，
 * 供 {@link PathingGoal} 决定“用哪格物品、是否需要先从主背包把它取到快捷栏、手持哪个槽”。
 *
 * <p>与挖块的 {@link DigTool} 平行：候选为<b>整个背包（0..35）</b>中“名称对应一个放置后
 * 可作为站立面的方块”（有碰撞、非流体）的物品堆，数量多的优先——数量决定可铺的桥长。</p>
 *
 * @param equipHotbarSlot  放置时应手持的快捷栏槽（0..8）；{@code -1} 表示无需改变手持
 * @param relocateFromSlot 当所选物品当前位于主背包（9..35）而需先换到 {@code equipHotbarSlot}
 *                         时，此处为源槽；否则 {@code -1}（无需搬迁）
 * @param placeable        是否找到了可用的可放置方块物品
 */
public record PlaceTool(int equipHotbarSlot, int relocateFromSlot, boolean placeable) {

    /** 无可用放置物品（或背包 / 物品表信息缺失）的规划结果。 */
    public static PlaceTool none() {
        return new PlaceTool(-1, -1, false);
    }

    /** 是否需要先把物品从主背包搬到快捷栏。 */
    public boolean needsRelocate() {
        return placeable && relocateFromSlot >= 9;
    }
}
