package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.ElytraFlightGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import top.mcocet.running.flight.BlockWorldQuery;
import top.mcocet.running.flight.ElytraDurability;
import top.mcocet.running.world.InventoryState;
import top.mcocet.running.world.item.ItemRegistry;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;
import xin.bbtt.mcbot.command.SubCommandExecutor;

import java.util.List;
import java.util.stream.Stream;

/**
 * fly 命令：鞘翅滑翔手动控制。
 *
 * <p>子命令（决策/预算架构移植 MovementSync 1.11 飞行子系统思路）：
 * <ul>
 *   <li>{@code fly start [yaw pitch]} —— 挂载 {@link ElytraFlightGoal}：检查/自动装备
 *       鞘翅（含耐久预算门控）→ 起跳→ 离地即时起滑（程序化请求，引擎同 tick 发
 *       START_ELYTRA_FLYING）→ 候选网格决策巡航；可选锁定巡航航向；</li>
 *   <li>{@code fly to <x> <z>} / {@code fly to <x> <y> <z>} —— 飞向指定坐标（y 缺省
 *       保持当前高度；三参形式与原版/g 指令习惯一致）；未在飞行时等价于带目标的 start；</li>
 *   <li>{@code fly stop} —— 转入损伤优先（emergency）着陆决策，等待自然触地；</li>
 *   <li>{@code fly boost [n]} —— 立即补发 n 发烟花（不等待决策器判势）；</li>
 *   <li>{@code fly force [on|off]} —— 强制飞行：旁路鞘翅耐久预算门控与迫降判定
 *       （仅对生存有意义；创造模式服务器不扣耐久，本就自动旁路）；</li>
 *   <li>{@code fly replace [on|off]} —— 耐久将尽时自动用背包备件换翅继续飞（默认开）；</li>
 *   <li>{@code fly status} —— 报告滑翔标志、速度/摔落距离、加速窗口、耐久预算、
 *       游戏模式、force/replace 开关与鞘翅/烟花持有。</li>
 * </ul>
 * 全部子命令要求已连接服务器；与 move/goto 目标互斥（挂载即手动接管）。</p>
 */
public final class FlyCommand extends SubCommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(FlyCommand.class.getSimpleName());

    @Override
    protected void onNoSubCommand(Command command, String label) {
        printUsage();
    }

    @Override
    protected void onUnknownSubCommand(Command command, String label, String unknownSub) {
        log.info("[Running] 未知子命令: {}", unknownSub);
        printUsage();
    }

    private static void printUsage() {
        log.info("[Running] fly 用法: fly <start [yaw]|to <x> <z> [y]|stop|boost [n]|force [on|off]|replace [on|off]|status>");
        log.info("[Running] start 装备起滑；to 飞向坐标；stop 损伤优先着陆；boost 补烟花；force 强制飞行（忽略耐久预算）；replace 耐久将尽自动换翅；status 状态。");
    }

    /** 校验插件/引擎就绪且已进入游戏，并返回插件实例；未就绪输出提示返回 null。 */
    private Main requirePlugin() {
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            log.warn("[Running] 插件实例不存在。");
            return null;
        }
        MovementEngine engine = plugin.movementEngine();
        if (engine == null) {
            log.warn("[Running] 移动引擎未就绪（插件尚未启用）。");
            return null;
        }
        if (!engine.isInGame()) {
            log.warn("[Running] 尚未进入游戏（未连接服务器或未登录），无法控制飞行。");
            return null;
        }
        if (plugin.config() == null) {
            log.warn("[Running] 功能配置未就绪。");
            return null;
        }
        return plugin;
    }

    /** 用插件的背包镜像/物品表/配置/世界查询/状态镜像重建飞行目标（每次 start 取最新配置）。 */
    private ElytraFlightGoal freshGoal(Main plugin) {
        return new ElytraFlightGoal(plugin.inventory(), plugin.itemRegistry(), plugin.config(),
                new BlockWorldQuery(plugin.world(), plugin.blockNames()), plugin.botState());
    }

    private final class StartCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = requirePlugin();
            if (plugin == null) return;
            MovementEngine engine = plugin.movementEngine();

            ElytraFlightGoal flightGoal = freshGoal(plugin);
            if (args.length >= 2) {
                try {
                    // 仅锁定巡航航向；俯仰已改由候选网格每 tick 决策，pitch 参数忽略
                    float yaw = Float.parseFloat(args[0]);
                    flightGoal.setHeading(yaw);
                } catch (NumberFormatException e) {
                    log.info("[Running] 航向参数无效，用法: fly start [yaw pitch]");
                    return;
                }
            } else if (args.length == 1) {
                log.info("[Running] 航向需要两个参数（yaw pitch，pitch 已废弃可传任意数），用法: fly start [yaw pitch]");
                return;
            }
            engine.setGoal(flightGoal);
            log.info("[Running] 已启动鞘翅飞行目标：检查装备 → 起跳 → 起滑（候选网格决策）。");
        }
    }

    /**
     * {@code fly to <x> <z>} / {@code fly to <x> <y> <z>}：飞向指定坐标
     * （y 缺省取当前高度；未在飞行则带目标起飞）。三参时轴序与 MC 习惯一致（x y z）。
     */
    private final class ToCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = requirePlugin();
            if (plugin == null) return;
            MovementEngine engine = plugin.movementEngine();
            if (args.length < 2 || args.length > 3) {
                log.info("[Running] 用法: fly to <x> <z> 或 fly to <x> <y> <z>");
                return;
            }
            double x;
            double y;
            double z;
            try {
                x = Double.parseDouble(args[0]);
                if (args.length == 3) {
                    y = Double.parseDouble(args[1]);
                    z = Double.parseDouble(args[2]);
                } else {
                    y = Double.NaN;
                    z = Double.parseDouble(args[1]);
                }
            } catch (NumberFormatException e) {
                log.info("[Running] 坐标无效，用法: fly to <x> <z> 或 fly to <x> <y> <z>");
                return;
            }
            if (engine.getGoal() instanceof ElytraFlightGoal flightGoal) {
                flightGoal.setTarget(x, y, z);
                log.info("[Running] 飞行目标已更新为 (x={}, z={})，y={}。", x, z,
                        Double.isNaN(y) ? "保持当前高度" : String.format("%.0f（锁定）", y));
                return;
            }
            ElytraFlightGoal flightGoal = freshGoal(plugin);
            flightGoal.setTarget(x, y, z);
            engine.setGoal(flightGoal);
            log.info("[Running] 起飞飞向 (x={}, z={})，y={}。", x, z,
                    Double.isNaN(y) ? "保持当前高度" : String.format("%.0f（锁定）", y));
        }
    }

    private final class StopCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = requirePlugin();
            if (plugin == null) return;
            MovementEngine engine = plugin.movementEngine();
            if (engine.getGoal() instanceof ElytraFlightGoal flightGoal) {
                flightGoal.requestStop();
                log.info("[Running] 已请求减速着陆（触地/入水后自动结束）。");
            } else {
                log.info("[Running] 当前不在 fly 飞行控制中（滑翔中可用 move stop 交回常规控制）。");
            }
        }
    }

    private final class BoostCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = requirePlugin();
            if (plugin == null) return;
            MovementEngine engine = plugin.movementEngine();
            if (!(engine.getGoal() instanceof ElytraFlightGoal flightGoal)) {
                log.info("[Running] 尚未 fly start，无法补发烟花。");
                return;
            }
            int count = 1;
            if (args.length >= 1) {
                try {
                    count = Math.max(1, Math.min(16, Integer.parseInt(args[0])));
                } catch (NumberFormatException e) {
                    log.info("[Running] 参数无效，用法: fly boost [n]");
                    return;
                }
            }
            flightGoal.requestBoost(count);
            log.info("[Running] 已请求补发 {} 发烟花（每次间隔自动节流）。", count);
        }

        @Override
        public List<String> onTabComplete(Command cmd, String label, String[] args) {
            if (args.length == 1) {
                return Stream.of("1", "2", "3").filter(v -> v.startsWith(args[0])).toList();
            }
            return List.of();
        }
    }

    private final class StatusCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = requirePlugin();
            if (plugin == null) return;
            PlayerPhysics physics = plugin.physics();
            if (physics == null || !physics.isPositioned()) {
                log.info("[Running] 尚未收到初始坐标。");
                return;
            }
            InventoryState inventory = plugin.inventory();
            ItemRegistry registry = plugin.itemRegistry();
            boolean elytra = inventory.isEquipped(registry, ElytraFlightGoal.ELYTRA_ITEM_NAME);
            int rockets = inventory.countItem(registry, "firework_rocket");
            ElytraDurability durability = ElytraDurability
                    .fromElytraStack(inventory.stackAt(InventoryState.ARMOR_CHEST));
            log.info("[Running] 飞行状态：滑翔={} 水平速度={}/tick 垂直速度={}/tick 摔落距离={} 加速窗口剩余={} tick",
                    physics.isFallFlying(),
                    String.format("%.3f", Math.hypot(physics.deltaX(), physics.deltaZ())),
                    String.format("%.3f", physics.deltaY()),
                    String.format("%.1f", physics.fallDistance()),
                    physics.rocketBoostRemaining());
            log.info("[Running] 鞘翅={}（胸槽，耐久 {}/{}，可飞预算 {} 秒） 烟花火箭={} 支；当前目标={}",
                    elytra ? "已装备" : "未装备", durability.remainingDurability(), durability.maxDamage(),
                    String.format("%.0f", durability.remainingFlightSeconds()), rockets,
                    plugin.movementEngine().getGoal() == null ? "无"
                            : plugin.movementEngine().getGoal().getClass().getSimpleName());
            log.info("[Running] 游戏模式={} 强制飞行={} 自动换翅={}（创造模式下耐久预算自动旁路）。",
                    plugin.botState().isCreative() ? "创造" : "生存/冒险",
                    physics.isForceFlight() ? "开" : "关",
                    physics.isAutoReplaceElytra() ? "开" : "关");
        }
    }

    /** on/off 切换子命令（创造模式下 force 提示冗余；开关存于物理层，跨目标重建持续）。 */
    private static final class ToggleCommand extends CommandExecutor {
        private final String display;
        private final java.util.function.BiConsumer<PlayerPhysics, Boolean> setter;
        private final java.util.function.Predicate<PlayerPhysics> getter;

        ToggleCommand(String display,
                      java.util.function.BiConsumer<PlayerPhysics, Boolean> setter,
                      java.util.function.Predicate<PlayerPhysics> getter) {
            this.display = display;
            this.setter = setter;
            this.getter = getter;
        }

        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = Main.INSTANCE;
            if (plugin == null || plugin.physics() == null) {
                log.warn("[Running] 物理模拟未就绪（插件尚未启用）。");
                return;
            }
            PlayerPhysics physics = plugin.physics();
            boolean value;
            if (args.length == 0) {
                value = !getter.test(physics);
            } else {
                Boolean parsed = switch (args[0].toLowerCase()) {
                    case "on", "true", "1" -> Boolean.TRUE;
                    case "off", "false", "0" -> Boolean.FALSE;
                    default -> null;
                };
                if (parsed == null) {
                    log.info("[Running] 参数无效，用法: {} [on|off]", label);
                    return;
                }
                value = parsed;
            }
            setter.accept(physics, value);
            log.info("[Running] {}已{}。", display, value ? "开启" : "关闭");
        }

        @Override
        public List<String> onTabComplete(Command cmd, String label, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Stream.of("on", "off").filter(v -> v.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }

    public FlyCommand() {
        registerSubCommand("start", new StartCommand());
        registerSubCommand("to", new ToCommand());
        registerSubCommand("stop", new StopCommand());
        registerSubCommand("boost", new BoostCommand());
        registerSubCommand("force", new ToggleCommand("强制飞行（忽略耐久预算门控）",
                PlayerPhysics::setForceFlight, PlayerPhysics::isForceFlight));
        registerSubCommand("replace", new ToggleCommand("耐久将尽自动换翅",
                PlayerPhysics::setAutoReplaceElytra, PlayerPhysics::isAutoReplaceElytra));
        registerSubCommand("status", new StatusCommand());
    }
}
