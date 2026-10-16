package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.RunningConfig;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.pathfinding.Goal;
import top.mcocet.running.pathfinding.GoalBlock;
import top.mcocet.running.pathfinding.GoalFollow;
import top.mcocet.running.pathfinding.GoalXZ;
import top.mcocet.running.pathfinding.PathingGoal;
import top.mcocet.running.pathfinding.RunningPathWorld;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * goto 命令：异步寻路到目标坐标（移植自 Baritone 的基础移动）。
 *
 * <p>用法：</p>
 * <ul>
 *   <li>{@code goto <x> <y> <z>} —— 走到该方块所在脚部位置（{@link GoalBlock}）；</li>
 *   <li>{@code goto <x> <z>} —— 走到该 XZ 列（Y 由寻路自行决定，{@link GoalXZ}）；</li>
 *   <li>{@code goto follow <玩家名>} —— 持续跟随该玩家（{@link GoalFollow}），
 *       到达时保持约 2 格间距，对方一移动自动重算，直到 {@code move stop}；</li>
 *   <li>{@code goto dig ...} / {@code goto move ...} —— 本次寻路临时使用
 *       <b>挖掘优先</b>（允许直接挖开方块前进到目的地）/ <b>移动优先</b>（能绕则绕），
 *       前缀作用于上述任一形式；缺省时跟随配置 {@code pathfinding.mode}。</li>
 * </ul>
 *
 * <p>命令发起异步寻路请求，由专用计算线程执行 A*，结果到达后由 {@link PathingGoal}
 * 状态机自动采纳并跟随。跨加载边界时自动续算，无需手动再次 {@code goto}。
 * 用 {@code move stop} 停止寻路。</p>
 */
public final class GotoCommand extends CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(GotoCommand.class.getSimpleName());

    /** 默认到达半径（仅配置不可用时兜底） */
    private static final double FALLBACK_ARRIVE_RADIUS = RunningConfig.DEFAULT_ARRIVE_RADIUS;
    /** 默认最大坠落高度（仅配置不可用时兜底） */
    private static final int FALLBACK_MAX_FALL_HEIGHT = RunningConfig.DEFAULT_MAX_FALL_HEIGHT;
    /** 默认直挖隧道总 tick 上限（仅配置不可用时兜底） */
    private static final int FALLBACK_TUNNEL_MAX_TICKS = RunningConfig.DEFAULT_TUNNEL_MAX_TICKS;
    /** 默认单缺口搭桥总 tick 预算（仅配置不可用时兜底） */
    private static final int FALLBACK_BRIDGE_MAX_TICKS = RunningConfig.DEFAULT_BRIDGE_MAX_TICKS;
    /** 默认同一缺口放置次数上限（仅配置不可用时兜底） */
    private static final int FALLBACK_MAX_PLACE_TRIES = RunningConfig.DEFAULT_MAX_PLACE_TRIES;
    /** 跟随玩家时保持的水平间距（格，切比雪夫半径） */
    private static final int FOLLOW_KEEP_RADIUS = 2;

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

        // 可选寻路模式前缀：goto dig / goto move 本次覆盖配置默认（pathfinding.mode），后接任一目标形式
        Boolean modeOverride = null;
        String[] rest = args;
        if (args.length >= 1 && args[0].equalsIgnoreCase("dig")) {
            modeOverride = true;
            rest = Arrays.copyOfRange(args, 1, args.length);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("move")) {
            modeOverride = false;
            rest = Arrays.copyOfRange(args, 1, args.length);
        }

        boolean continuous = false;
        Goal goal;
        if (rest.length >= 1 && rest[0].equalsIgnoreCase("follow")) {
            if (rest.length < 2) {
                usage(command);
                return;
            }
            GoalFollow followGoal = new GoalFollow(rest[1], plugin.playerList(),
                    plugin.entityTracker(), FOLLOW_KEEP_RADIUS);
            if (!followGoal.isTargetLoaded()) {
                log.info("[Running] 未找到玩家 {} 或其不在已加载范围内，无法跟随。", rest[1]);
                return;
            }
            goal = followGoal;
            continuous = true;
        } else {
            goal = parseGoal(command, rest);
            if (goal == null) {
                return;
            }
        }

        int startX = (int) Math.floor(physics.x());
        int startZ = (int) Math.floor(physics.z());

        // 从功能配置读取寻路开关；配置不可用时逐层回退到内置默认
        RunningConfig config = plugin.config();
        boolean allowSprint = config != null ? config.allowSprint() : RunningConfig.DEFAULT_ALLOW_SPRINT;
        boolean allowBreak = config != null ? config.allowBreak() : RunningConfig.DEFAULT_ALLOW_BREAK;
        // 挖掘优先：命令前缀优先，否则跟随配置 pathfinding.mode（配置缺失时默认移动优先）
        boolean digPriority = modeOverride != null ? modeOverride
                : (config != null && config.digPriority());
        // 挖掘优先模式下“卡住应急挖通”自然开启（该模式本就允许直挖前进）
        boolean stuckDig = (config != null ? config.stuckDigRecovery() : RunningConfig.DEFAULT_STUCK_DIG)
                || digPriority;
        // 直挖无路可踩的缺口处，是否允许用背包方块放置地板搭桥
        boolean allowPlace = config != null ? config.allowPlace() : RunningConfig.DEFAULT_ALLOW_PLACE;
        boolean allowParkour = config != null ? config.allowParkour() : RunningConfig.DEFAULT_ALLOW_PARKOUR;
        int maxFallHeight = config != null ? config.maxFallHeight() : FALLBACK_MAX_FALL_HEIGHT;
        double arriveRadius = config != null ? config.arriveRadius() : FALLBACK_ARRIVE_RADIUS;
        // 允许长时间放置方块的上限：直挖总时长 / 单缺口搭桥预算 / 同缺口放置次数
        int tunnelMaxTicks = config != null ? config.tunnelMaxTicks() : FALLBACK_TUNNEL_MAX_TICKS;
        int bridgeMaxTicks = config != null ? config.bridgeMaxTicks() : FALLBACK_BRIDGE_MAX_TICKS;
        int maxPlaceTries = config != null ? config.maxPlaceTries() : FALLBACK_MAX_PLACE_TRIES;

        RunningPathWorld world = new RunningPathWorld(
                plugin.world(), plugin.blockNames(),
                plugin.inventory().hotbarToolModels(plugin.itemRegistry()),
                plugin.inventory(), plugin.itemRegistry(),
                allowSprint, allowBreak, digPriority, allowPlace, allowParkour, maxFallHeight);
        if (!world.isLoaded(startX, startZ)) {
            log.info("[Running] 起点所在区块尚未加载，无法寻路。");
            return;
        }

        String modeLabel = digPriority ? "挖掘优先" : "移动优先";
        if (continuous) {
            log.info("[Running] 正在持续跟随玩家 {}（保持约 {} 格，模式：{}）...",
                    rest[1], FOLLOW_KEEP_RADIUS, modeLabel);
        } else {
            log.info("[Running] 正在寻路到 {}（模式：{}）...", goal, modeLabel);
        }
        PathingGoal pathingGoal = new PathingGoal(goal, world, plugin.inventory(),
                arriveRadius, plugin.pathExecutor(), continuous, stuckDig,
                tunnelMaxTicks, bridgeMaxTicks, maxPlaceTries);
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
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            return List.of();
        }
        // 首参：提示 follow 子命令与模式前缀 dig / move
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            List<String> out = new ArrayList<>();
            for (String s : new String[]{"follow", "dig", "move"}) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        // 第二参且首参为 follow：按已记录的玩家名补全
        if (args.length == 2 && args[0].equalsIgnoreCase("follow")) {
            String prefix = args[1].toLowerCase();
            List<String> names = new ArrayList<>();
            for (String name : plugin.playerList().knownNames()) {
                if (name != null && name.toLowerCase().startsWith(prefix)) {
                    names.add(name);
                }
            }
            return names;
        }
        // 形如 goto dig/move <...>：次参可补全 follow
        if (args.length == 2
                && (args[0].equalsIgnoreCase("dig") || args[0].equalsIgnoreCase("move"))) {
            String prefix = args[1].toLowerCase();
            return "follow".startsWith(prefix) ? List.of("follow") : List.of();
        }
        return List.of();
    }
}
