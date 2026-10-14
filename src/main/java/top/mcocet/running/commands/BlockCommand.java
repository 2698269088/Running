package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.BotState;
import top.mcocet.running.math.AABB;
import top.mcocet.running.world.CollisionShapes;
import top.mcocet.running.world.RunningWorld;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * block 命令：查询脚下方块，或指定坐标处的方块。
 *
 * <p>用法：{@code block}（脚下）或 {@code block <x> <y> <z>}。
 * 输出方块状态 ID、几何来源、碰撞盒数与顶面高度；若数据表可用，
 * 则同时显示方块名称。这一行就能同时验证两件事：
 * 状态 ID 与看到的方块是否对得上（数据版本对不对），
 * 以及形状是否合理（几何取权威表还是退化到启发式）。</p>
 */
public final class BlockCommand extends CommandExecutor {
    private final Logger log = LoggerFactory.getLogger(BlockCommand.class.getSimpleName());

    @Override
    public void onCommand(Command command, String label, String[] args) {
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            log.warn("[Running] 插件实例不存在。");
            return;
        }

        int x;
        int y;
        int z;

        if (args.length == 0) {
            BotState state = plugin.botState();
            if (!state.hasPosition()) {
                log.info("[Running] 尚未收到服务器下发的初始坐标（未登录或世界尚未就绪）。");
                return;
            }
            x = (int) Math.floor(state.getX());
            y = (int) Math.floor(state.getY()) - 1;
            z = (int) Math.floor(state.getZ());
        } else if (args.length >= 3) {
            try {
                x = (int) Math.floor(Double.parseDouble(args[0]));
                y = (int) Math.floor(Double.parseDouble(args[1]));
                z = (int) Math.floor(Double.parseDouble(args[2]));
            } catch (NumberFormatException e) {
                log.info("[Running] 坐标格式错误，用法: {}", command.getUsage());
                return;
            }
        } else {
            log.info("[Running] 用法: {}", command.getUsage());
            return;
        }

        RunningWorld world = plugin.world();
        int stateId = world.getBlockStateId(x, y, z);
        if (stateId == RunningWorld.UNLOADED) {
            log.info("[Running] ({}, {}, {}) 所在区块尚未加载。", x, y, z);
            return;
        }

        String name = plugin.blockNames().nameOf(stateId);
        if (name != null) {
            CollisionShapes shapes = CollisionShapes.get();
            List<AABB> boxes = new ArrayList<>(4);
            plugin.blockNames().addCollisionBoxes(stateId, 0, 0, 0, boxes);
            log.info("[Running] ({}, {}, {}) 的方块: {}（状态 ID {}，{} 个碰撞盒，顶面高度 {}，几何来源 {}）",
                    x, y, z, name, stateId, boxes.size(), plugin.blockNames().collisionHeight(stateId),
                    shapes != null && shapes.covers(stateId) ? "权威表" : "启发式兜底");
        } else if (stateId == RunningWorld.AIR) {
            log.info("[Running] ({}, {}, {}) 的方块: 空气（状态 ID 0）", x, y, z);
        } else if (plugin.blockNames().isEmpty()) {
            log.info("[Running] ({}, {}, {}) 的方块: 未知（状态 ID {}，放置 blocks.json 可显示名称）",
                    x, y, z, stateId);
        } else {
            log.info("[Running] ({}, {}, {}) 的方块: 未知（状态 ID {}）", x, y, z, stateId);
        }
    }
}
