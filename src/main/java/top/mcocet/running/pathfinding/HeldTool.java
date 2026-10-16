package top.mcocet.running.pathfinding;

import top.mcocet.running.world.BlockNameTable;

/**
 * 手持工具对破块速度的影响快照（对齐原版 {@code Player.getDigSpeed} 与
 * {@code hasCorrectToolForDrops} 所需的最小信息）。
 *
 * <p>寻路在 {@link PathFinder}（专用计算线程）中把破块时间计入代价，需要一个
 * 计算开始时抓取的、只读的当前工具快照；执行阶段真正挥镐时的工具由移动执行器
 * 依据背包实时决定（Stage 2）。本类是那个快照的值对象。</p>
 *
 * <p>原版语义：某方块的有效挖掘速度 {@code digSpeed} 只有当手持工具的采集族
 * 与方块的 {@code mineable/*} 族匹配时才取工具速度，否则退化为徒手速度（1.0）；
 * 是否有「正确工具」（决定掉落、进而决定 {@code /30} 与 {@code /100} 除数）
 * 在方块不需要工具时恒为真，否则要求工具族匹配且工具等级达到门槛。本快照以
 * 工具族匹配近似「正确工具」，等级门槛留作后续（需要 harvestTools 的物品级映射）。</p>
 */
public final class HeldTool {

    /** 工具等级（对齐 TieredItem 的大致分档，用于后续按方块门槛判定可采集） */
    public static final int TIER_NONE = 0;
    public static final int TIER_WOOD = 1;
    public static final int TIER_STONE = 2;
    public static final int TIER_IRON = 3;
    public static final int TIER_DIAMOND = 4;
    public static final int TIER_NETHERITE = 5;
    /** 金工具与木同级速度/采集能力，单独标记便于后续区分 */
    public static final int TIER_GOLD = 1;

    /** 徒手：无工具族，速度 1.0，等级 0 */
    public static final HeldTool HAND = new HeldTool(BlockNameTable.TOOL_NONE, 1.0, TIER_NONE);

    private final byte category;
    private final double speed;
    private final int tier;

    public HeldTool(byte category, double speed, int tier) {
        this.category = category;
        this.speed = speed;
        this.tier = tier;
    }

    /** 该工具的采集族（{@code BlockNameTable.TOOL_*}）；徒手/非工具为 NONE */
    public byte category() {
        return category;
    }

    /** 工具在其有效方块上的挖掘速度（徒手为 1.0） */
    public double speed() {
        return speed;
    }

    public int tier() {
        return tier;
    }

    /**
     * 对某采集族方块的有效挖掘速度：族匹配则用工具速度，否则退化为徒手 1.0。
     * （对齐 1.21 {@code ToolItem}：工具速度仅作用于其 effective blocks。）
     */
    public double digSpeedFor(byte blockCategory) {
        return isEffectiveFor(blockCategory) ? speed : 1.0;
    }

    /** 工具是否为该类方块的有效工具（决定速度加成） */
    public boolean isEffectiveFor(byte blockCategory) {
        return category != BlockNameTable.TOOL_NONE && blockCategory != BlockNameTable.TOOL_NONE
                && category == blockCategory;
    }

    /**
     * 是否为该类方块的「正确工具」（可获得掉落）。方块不需要工具时恒为真；
     * 否则要求工具族匹配。等级门槛留作后续精化。
     */
    public boolean correctForDrops(byte blockCategory, boolean blockRequiresTool) {
        if (!blockRequiresTool) {
            return true;
        }
        return isEffectiveFor(blockCategory);
    }
}
