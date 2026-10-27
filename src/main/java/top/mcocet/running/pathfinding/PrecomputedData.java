package top.mcocet.running.pathfinding;

import top.mcocet.running.world.BlockNameTable;

/**
 * 方块状态预计算位掩码缓存，移植自 Baritone 的 {@code PrecomputedData}。
 *
 * <p>把每个方块状态 ID 的 {@code canWalkOn} / {@code canWalkThrough} / {@code fullyPassable}
 * / {@code canPlaceAgainst} / {@code neverBreak} 等判定结果编码为位掩码，缓存到 {@code int[]}
 * 数组中，按方块状态 ID 索引。A* 搜索中每个节点要查询数十次方块状态，预计算缓存能显著减少
 * CPU 开销（Baritone 注释提到 {@code isBlockNormalCube} 的 Guava 缓存曾占 8% 搜索时间）。</p>
 *
 * <p>位掩码布局（低位到高位）：</p>
 * <ul>
 *   <li>bit 0: COMPLETED - 该状态已计算过</li>
 *   <li>bit 1: CAN_WALK_ON_MAYBE - 需要位置信息才能确定是否可站立</li>
 *   <li>bit 2: CAN_WALK_ON - 确定可站立</li>
 *   <li>bit 3: CAN_WALK_THROUGH_MAYBE - 需要位置信息才能确定是否可通行</li>
 *   <li>bit 4: CAN_WALK_THROUGH - 确定可通行</li>
 *   <li>bit 5: FULLY_PASSABLE_MAYBE - 需要位置信息才能确定是否完全可通过</li>
 *   <li>bit 6: FULLY_PASSABLE - 确定完全可通过</li>
 *   <li>bit 7: CAN_PLACE_AGAINST - 可作为放置依附面</li>
 *   <li>bit 8: NEVER_BREAK - 不应破坏（基岩、命令方块等）</li>
 * </ul>
 */
public final class PrecomputedData {

    private static final int COMPLETED_MASK = 1;
    private static final int CAN_WALK_ON_MAYBE_MASK = 1 << 1;
    private static final int CAN_WALK_ON_MASK = 1 << 2;
    private static final int CAN_WALK_THROUGH_MAYBE_MASK = 1 << 3;
    private static final int CAN_WALK_THROUGH_MASK = 1 << 4;
    private static final int FULLY_PASSABLE_MAYBE_MASK = 1 << 5;
    private static final int FULLY_PASSABLE_MASK = 1 << 6;
    private static final int CAN_PLACE_AGAINST_MASK = 1 << 7;
    private static final int NEVER_BREAK_MASK = 1 << 8;

    /** 按方块状态 ID 索引的位掩码缓存 */
    private final int[] data;
    private final BlockNameTable blocks;

    public PrecomputedData(BlockNameTable blocks) {
        this.blocks = blocks;
        // 按状态 ID 上界分配（blocks.json 加载时已确定）
        int maxStateId = blocks.size() - 1;
        this.data = new int[maxStateId + 1];
    }

    /**
     * 获取指定状态 ID 的位掩码；若未计算则现场计算并缓存。
     */
    private int flags(int stateId) {
        if (stateId < 0 || stateId >= data.length) {
            return 0;
        }
        int blockData = data[stateId];
        if ((blockData & COMPLETED_MASK) == 0) {
            blockData = fillData(stateId);
            data[stateId] = blockData;
        }
        return blockData;
    }

    /**
     * 计算指定状态 ID 的位掩码。
     */
    private int fillData(int stateId) {
        int blockData = 0;

        // canWalkOn
        boolean canWalkOn = computeCanWalkOn(stateId);
        if (canWalkOn) {
            blockData |= CAN_WALK_ON_MASK;
        }

        // canWalkThrough
        boolean canWalkThrough = computeCanWalkThrough(stateId);
        if (canWalkThrough) {
            blockData |= CAN_WALK_THROUGH_MASK;
        }

        // fullyPassable
        boolean fullyPassable = computeFullyPassable(stateId);
        if (fullyPassable) {
            blockData |= FULLY_PASSABLE_MASK;
        }

        // canPlaceAgainst
        boolean canPlaceAgainst = computeCanPlaceAgainst(stateId);
        if (canPlaceAgainst) {
            blockData |= CAN_PLACE_AGAINST_MASK;
        }

        // neverBreak
        boolean neverBreak = computeNeverBreak(stateId);
        if (neverBreak) {
            blockData |= NEVER_BREAK_MASK;
        }

        blockData |= COMPLETED_MASK;
        return blockData;
    }

    private boolean computeCanWalkOn(int stateId) {
        if (stateId == PathWorld.AIR) {
            return false;
        }
        if (blocks.fluidType(stateId) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(stateId)) {
            return false;
        }
        return blocks.hasCollision(stateId);
    }

    private boolean computeCanWalkThrough(int stateId) {
        if (stateId == PathWorld.AIR) {
            return true;
        }
        if (blocks.fluidType(stateId) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(stateId)) {
            return false;
        }
        return !blocks.blocksMotion(stateId);
    }

    private boolean computeFullyPassable(int stateId) {
        if (stateId == PathWorld.AIR) {
            return true;
        }
        // 完全可通过：无碰撞、无流体、非危险、非攀爬
        if (blocks.fluidType(stateId) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        if (isDangerous(stateId)) {
            return false;
        }
        if (isClimbable(stateId)) {
            return false;
        }
        return !blocks.blocksMotion(stateId);
    }

    private boolean computeCanPlaceAgainst(int stateId) {
        if (stateId == PathWorld.AIR) {
            return false;
        }
        // 可作为放置依附面：完整立方体或玻璃
        // 注：BlockNameTable 没有 isFullCube 方法，用 hasCollision 近似
        return blocks.hasCollision(stateId) || isGlass(stateId);
    }

    private boolean computeNeverBreak(int stateId) {
        if (stateId == PathWorld.AIR) {
            return true;
        }
        // 不应破坏：基岩、命令方块、结构方块等
        String name = blocks.nameOf(stateId);
        if (name == null) {
            return false;
        }
        return name.contains("bedrock")
                || name.contains("command_block")
                || name.contains("structure_block")
                || name.contains("structure_void")
                || name.contains("barrier")
                || name.contains("end_portal")
                || name.contains("end_gateway")
                || name.contains("nether_portal");
    }

    private boolean isDangerous(int stateId) {
        if (blocks.fluidType(stateId) == BlockNameTable.FLUID_LAVA) {
            return true;
        }
        String name = blocks.nameOf(stateId);
        if (name == null) {
            return false;
        }
        return name.contains("fire")
                || name.contains("lava")
                || name.contains("magma")
                || name.contains("cactus")
                || name.contains("sweet_berry_bush")
                || name.contains("powder_snow");
    }

    private boolean isClimbable(int stateId) {
        String name = blocks.nameOf(stateId);
        return "ladder".equals(name) || "vine".equals(name) || "scaffolding".equals(name);
    }

    private boolean isGlass(int stateId) {
        String name = blocks.nameOf(stateId);
        return name != null && (name.equals("glass") || name.endsWith("_glass"));
    }

    // ------------------------------------------------------------------
    // 公共查询接口
    // ------------------------------------------------------------------

    public boolean canWalkOn(int stateId) {
        return (flags(stateId) & CAN_WALK_ON_MASK) != 0;
    }

    public boolean canWalkThrough(int stateId) {
        return (flags(stateId) & CAN_WALK_THROUGH_MASK) != 0;
    }

    public boolean fullyPassable(int stateId) {
        return (flags(stateId) & FULLY_PASSABLE_MASK) != 0;
    }

    public boolean canPlaceAgainst(int stateId) {
        return (flags(stateId) & CAN_PLACE_AGAINST_MASK) != 0;
    }

    public boolean neverBreak(int stateId) {
        return (flags(stateId) & NEVER_BREAK_MASK) != 0;
    }
}
