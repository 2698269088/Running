package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.pathfinding.Goal;
import top.mcocet.running.pathfinding.GoalBlock;
import top.mcocet.running.pathfinding.GoalXZ;
import top.mcocet.running.pathfinding.PathingGoal;
import top.mcocet.running.pathfinding.RunningPathWorld;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

import java.util.List;

/**
 * goto 命令：异步寻路到目标坐标（移植自 Baritone 的基础移动）。
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code goto <x> <y> <z>} —— 走到该方块所在脚部位置（{@link GoalBlock}）；</li>
 *   <li>{@code goto <x> <z>} —— 走到该 XZ 列（Y 由寻路自行决定，{@link GoalXZ}）。</li>
 * </ul>
 *
 * <p>命令发起异步寻路请求，由专用计算线程执行 A*，结果到达后由 {@link PathingGoal}
 * 状态机自动采纳并跟随。跨加载边界时自动续算，无需手动再次 {@code goto}。
 * 用 {@code move stop} 停止寻路。</p>
 */
public final class GotoCommand extends CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(GotoCommand.class.getSimpleName());

    /** 节点到达半径（格） */
    private static final double ARRIVE_RADIUS = 0.5;
    /** 允许的最大无伤坠落高度 */
    private static final int MAX_FALL_HEIGHT = 3;

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
            log.warn("[Running] 尚未进入游戏，无法寻路。");
            return;
        }
        PlayerPhysics physics = plugin.physics();
        if (physics == null || !physics.isPositioned()) {
            log.info("[Running] 尚未收到初始坐标，无法确定寻路起点。");
            return;
        }

        Goal goal = parseGoal(command, args);
        if (goal == null) {
            return;
        }

        int startX = (int) Math.floor(physics.x());
        int startZ = (int) Math.floor(physics.z());

        RunningPathWorld world = new RunningPathWorld(
                plugin.world(), plugin.blockNames(),
                plugin.inventory().hotbarToolModels(plugin.itemRegistry()),
                true, MAX_FALL_HEIGHT);
        if (!world.isLoaded(startX, startZ)) {
            log.info("[Running] 起点所在区块尚未加载，无法寻路。");
            return;
        }

        log.info("[Running] 正在寻路到 {} ...", goal);
        PathingGoal pathingGoal = new PathingGoal(goal, world, ARRIVE_RADIUS, plugin.pathExecutor());
        engine.setGoal(pathingGoal);
    }

    /** 解析目标；非法时输出用法并返回 null。 */
    private Goal parseGoal(Command command, String[] args) {
        if (args.length == 2) {
            int[] xz = parseInts(args[0], args[1]);
            if (xz == null) {
                usage(command);
                return null;
            }
            return new GoalXZ(xz[0], xz[1]);
        }
        if (args.length >= 3) {
            int[] xyz = parseInts(args[0], args[1], args[2]);
            if (xyz == null) {
                usage(command);
                return null;
            }
            return new GoalBlock(xyz[0], xyz[1], xyz[2]);
        }
        usage(command);
        return null;
    }

    private void usage(Command command) {
        log.info("[Running] 用法: {}", command.getUsage());
    }

    private static int[] parseInts(String... values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                out[i] = (int) Math.floor(Double.parseDouble(values[i]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(Command cmd, String label, String[] args) {
        return List.of();
    }
}
