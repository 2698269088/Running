package top.mcocet.running.commands;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.BlockActionGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.pathfinding.MiningCalculator;
import top.mcocet.running.world.BlockNameTable;
import top.mcocet.running.world.RunningWorld;
import top.mcocet.running.world.item.ToolModel;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

import java.util.List;

/**
 * act 命令：在协议层手动测试“挖掘 / 放置”方块（连通性验证，非寻路自动执行）。
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code act dig <x> <y> <z>} —— 破坏该坐标的方块；按住时长按<b>实际手持工具</b>的
 *       每 tick 破坏进度累加，进度满即发 FINISH（对齐原版，不会提前也不会多按）；</li>
 *   <li>{@code act place <x> <y> <z>} —— 把当前手持物品放到该坐标（自动挑选一个相邻实心面点击）；</li>
 *   <li>{@code act stop} —— 取消当前正在进行的挖掘/放置。</li>
 * </ul>
 *
 * <p>执行由 {@link MovementEngine} 以 20 TPS 驱动（见 {@link BlockActionGoal}），
 * 所有交互包都在引擎 tick 内发出并与 tick 结束包正确配对。目标须在触及距离内且区块已加载。</p>
 */
public final class ActCommand extends CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(ActCommand.class.getSimpleName());

    @Override
    public void onCommand(Command command, String label, String[] args) {
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            log.warn("[Running] 插件实例不存在。");
            return;
        }
        MovementEngine engine = plugin.movementEngine();
        if (engine == null) {
            log.warn("[Running] 移动引擎未就绪（插件尚未启用）。");
            return;
        }
        if (!engine.isInGame()) {
            log.warn("[Running] 尚未进入游戏，无法交互。");
            return;
        }

        if (args.length == 0) {
            usage(command);
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "stop" -> {
                engine.setGoal(null);
                log.info("[Running] 已取消当前方块交互。");
            }
            case "dig" -> doDig(plugin, engine, args);
            case "place" -> doPlace(plugin, engine, args);
            default -> usage(command);
        }
    }

    private void doDig(Main plugin, MovementEngine engine, String[] args) {
        int[] xyz = parseCoords(args);
        if (xyz == null) {
            log.info("[Running] 坐标格式错误。");
            usageOf();
            return;
        }
        PlayerPhysics physics = engine.physics();
        if (physics == null || !physics.isPositioned()) {
            log.info("[Running] 尚未取得坐标，无法确定朝向。");
            return;
        }
        int x = xyz[0];
        int y = xyz[1];
        int z = xyz[2];

        RunningWorld world = plugin.world();
        BlockNameTable blocks = plugin.blockNames();
        int stateId = world.getBlockStateId(x, y, z);
        if (stateId == RunningWorld.UNLOADED) {
            log.info("[Running] ({}, {}, {}) 所在区块尚未加载。", x, y, z);
            return;
        }
        if (stateId == RunningWorld.AIR) {
            log.info("[Running] ({}, {}, {}) 是空气，无需挖掘。", x, y, z);
            return;
        }
        if (!blocks.isDiggable(stateId)) {
            log.info("[Running] ({}, {}, {}) 不可破坏（{}）。", x, y, z, nameOrId(blocks, stateId));
            return;
        }

        String name = blocks.nameOf(stateId);
        float hardness = blocks.hardness(stateId);
        byte category = blocks.toolCategory(stateId);
        boolean requiresTool = blocks.requiresCorrectTool(stateId);
        // 对齐原版客户端：按“实际手持工具”的每 tick 破坏进度估算（徒手时为基线 1.0）。
        // 服务端用同一公式自主推进裂纹，进度满即发 FINISH，两者同步：
        // 既不会比服务端允许得更快（fastbreak），也不会在裂纹已满后多按数秒才破块。
        ToolModel heldTool = plugin.inventory().heldTool(plugin.itemRegistry());
        double perTick = MiningCalculator.destroyProgress(heldTool, name, category, hardness, requiresTool, true);
        if (perTick <= 0.0) {
            log.info("[Running] ({}, {}, {}) 无法估算破坏时间，已放弃。", x, y, z);
            return;
        }

        Direction face = faceTowardPlayer(physics, x, y, z);
        engine.setGoal(BlockActionGoal.dig(Vector3i.from(x, y, z), face, perTick));
        log.info("[Running] 正在挖掘 ({}, {}, {})（{}，手持 {}），预计约 {} tick。",
                x, y, z, nameOrId(blocks, stateId),
                heldTool != null ? String.valueOf(plugin.inventory().heldItemName(plugin.itemRegistry())) : "徒手",
                (int) Math.ceil(1.0 / perTick));
    }

    private void doPlace(Main plugin, MovementEngine engine, String[] args) {
        int[] xyz = parseCoords(args);
        if (xyz == null) {
            log.info("[Running] 坐标格式错误。");
            usageOf();
            return;
        }
        PlayerPhysics physics = engine.physics();
        if (physics == null || !physics.isPositioned()) {
            log.info("[Running] 尚未取得坐标，无法确定朝向。");
            return;
        }
        int x = xyz[0];
        int y = xyz[1];
        int z = xyz[2];

        if (plugin.inventory().heldItemId() <= 0) {
            log.info("[Running] 当前手持为空，无法放置（请先手持一个可放置方块）。");
            return;
        }

        RunningWorld world = plugin.world();
        BlockNameTable blocks = plugin.blockNames();
        int targetState = world.getBlockStateId(x, y, z);
        if (targetState == RunningWorld.UNLOADED) {
            log.info("[Running] ({}, {}, {}) 所在区块尚未加载。", x, y, z);
            return;
        }

        // 选一个相邻实心面来点击：clicked + faceNormal = 目标空位。按优先级尝试。
        Placement support = findSupportFace(world, blocks, x, y, z);
        if (support == null) {
            log.info("[Running] ({}, {}, {}) 周围没有可依附的实心方块，无法放置。", x, y, z);
            return;
        }

        engine.setGoal(BlockActionGoal.place(support.clickedPos, support.face,
                support.cursorX, support.cursorY, support.cursorZ));
        log.info("[Running] 正在向 ({}, {}, {}) 放置（点击 {} 的 {} 面）。",
                x, y, z, describe(support.clickedPos), support.face);
    }

    // ------------------------------------------------------------------
    // 几何辅助
    // ------------------------------------------------------------------

    /** 眼睛到方块中心的主轴方向，取“朝向玩家”的那一面（挖掘时 face 仅作参考，服务器主要看位置）。 */
    private static Direction faceTowardPlayer(PlayerPhysics physics, int bx, int by, int bz) {
        double dx = physics.x() - (bx + 0.5D);
        double dy = (physics.y() + 1.62D) - (by + 0.5D);
        double dz = physics.z() - (bz + 0.5D);
        double adx = Math.abs(dx);
        double ady = Math.abs(dy);
        double adz = Math.abs(dz);
        if (ady >= adx && ady >= adz) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (adx >= adz) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** 放置面及其点击信息。 */
    private static final class Placement {
        final Vector3i clickedPos;
        final Direction face;
        final float cursorX;
        final float cursorY;
        final float cursorZ;

        Placement(Vector3i clickedPos, Direction face) {
            this.clickedPos = clickedPos;
            this.face = face;
            float[] c = cursorForFace(face);
            this.cursorX = c[0];
            this.cursorY = c[1];
            this.cursorZ = c[2];
        }
    }

    /**
     * 为目标空位找一个相邻实心面。优先级：下面上方放置（UP，利于垫脚/搭柱）、
     * 朝向玩家的一面、其余水平面、最后顶面向下（DOWN）。
     */
    private static Placement findSupportFace(RunningWorld world, BlockNameTable blocks, int x, int y, int z) {
        // 目标空位应为空气/可放置（非固体）。不强制，交给服务器判定。
        Direction[] priority = {Direction.UP, Direction.SOUTH, Direction.NORTH,
                Direction.EAST, Direction.WEST, Direction.DOWN};
        for (Direction face : priority) {
            int[] n = normal(face);
            int cx = x - n[0];
            int cy = y - n[1];
            int cz = z - n[2];
            if (isSolidForSupport(world, blocks, cx, cy, cz)) {
                return new Placement(Vector3i.from(cx, cy, cz), face);
            }
        }
        return null;
    }

    private static boolean isSolidForSupport(RunningWorld world, BlockNameTable blocks, int x, int y, int z) {
        int state = world.getBlockStateId(x, y, z);
        if (state == RunningWorld.UNLOADED || state == RunningWorld.AIR) {
            return false;
        }
        if (blocks.fluidType(state) != BlockNameTable.FLUID_NONE) {
            return false;
        }
        return blocks.hasCollision(state);
    }

    private static int[] normal(Direction face) {
        return switch (face) {
            case DOWN -> new int[]{0, -1, 0};
            case UP -> new int[]{0, 1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case WEST -> new int[]{-1, 0, 0};
            case EAST -> new int[]{1, 0, 0};
        };
    }

    /** 面中心命中点（相对被点方块）；法向轴取贴近面内侧的 0.001/0.999 避免边界抖动。 */
    private static float[] cursorForFace(Direction face) {
        return switch (face) {
            case DOWN -> new float[]{0.5F, 0.001F, 0.5F};
            case UP -> new float[]{0.5F, 0.999F, 0.5F};
            case NORTH -> new float[]{0.5F, 0.5F, 0.001F};
            case SOUTH -> new float[]{0.5F, 0.5F, 0.999F};
            case WEST -> new float[]{0.001F, 0.5F, 0.5F};
            case EAST -> new float[]{0.999F, 0.5F, 0.5F};
        };
    }

    private static String describe(Vector3i p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    private static String nameOrId(BlockNameTable blocks, int stateId) {
        String name = blocks.nameOf(stateId);
        return name != null ? name : ("状态 ID " + stateId);
    }

    /** 解析 args[1..3] 为取整坐标；args 不足或非数字返回 null。 */
    private static int[] parseCoords(String[] args) {
        if (args.length < 4) {
            return null;
        }
        try {
            return new int[]{
                    (int) Math.floor(Double.parseDouble(args[1])),
                    (int) Math.floor(Double.parseDouble(args[2])),
                    (int) Math.floor(Double.parseDouble(args[3]))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void usage(Command command) {
        log.info("[Running] 用法: {}", command.getUsage());
    }

    private void usageOf() {
        log.info("[Running] 用法: act <dig|place> <x y z> 或 act stop");
    }

    @Override
    public List<String> onTabComplete(Command cmd, String label, String[] args) {
        return List.of();
    }
}
