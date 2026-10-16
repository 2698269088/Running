package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.ManualControlGoal;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;
import xin.bbtt.mcbot.command.SubCommandExecutor;

import java.util.List;
import java.util.stream.Stream;

/**
 * move 命令：控制机器人移动。
 *
 * <p>子命令与键盘按键一一对应，语义为"按住"：
 * <ul>
 *   <li>{@code move forward|back|left|right [on|off]} —— 前后左右移动键，
 *       不加参数为切换状态；</li>
 *   <li>{@code move jump} —— 请求一次跳跃（在可起跳时执行）；</li>
 *   <li>{@code move sprint [on|off]} —— 疾跑键（需配合前进输入才会进入冲刺）；</li>
 *   <li>{@code move sneak [on|off]} —— 潜行键（同时缓行并阻止进入冲刺）；</li>
 *   <li>{@code move autojump [on|off]} —— 自动跳跃开关（开启后前进遇到
 *       可跳上的障碍会自动起跳，对齐官方"自动跳跃"选项，默认开启）；</li>
 *   <li>{@code move stop} —— 复位全部按键并取消当前移动目标。</li>
 * </ul>
 * 按键状态由共享的 {@link ManualControlGoal} 维护，首次操作时自动挂载到
 * 移动引擎（替换既有目标，即手动接管）；物理模拟、输入包与位置包发送
 * 均由引擎自动完成。</p>
 */
public final class MoveCommand extends SubCommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(MoveCommand.class.getSimpleName());

    /** 手动控制目标：全部子命令共享同一实例 */
    private final ManualControlGoal goal = new ManualControlGoal();

    public MoveCommand() {
        registerSubCommand("forward", new KeyCommand(Key.FORWARD));
        registerSubCommand("back", new KeyCommand(Key.BACKWARD));
        registerSubCommand("left", new KeyCommand(Key.LEFT));
        registerSubCommand("right", new KeyCommand(Key.RIGHT));
        registerSubCommand("jump", new JumpCommand());
        registerSubCommand("sprint", new KeyCommand(Key.SPRINT));
        registerSubCommand("sneak", new KeyCommand(Key.SNEAK));
        registerSubCommand("autojump", new AutoJumpCommand());
        registerSubCommand("stop", new StopCommand());
    }

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
        log.info("[Running] move 用法: move <forward|back|left|right|jump|sprint|sneak|autojump|stop> [on|off]");
        log.info("[Running] 移动/疾跑/潜行/自动跳跃 不加参数为切换状态，或显式指定 on|off；jump 请求一次跳跃；stop 停止全部移动。");
    }

    /**
     * 校验插件与引擎就绪且已进入游戏。
     *
     * @return 移动引擎；未就绪时输出提示并返回 null
     */
    private MovementEngine requireEngine() {
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
            log.warn("[Running] 尚未进入游戏（未连接服务器或未登录），无法控制移动。");
            return null;
        }
        return engine;
    }

    /** 若引擎当前目标不是手动控制目标，则替换为它（手动接管） */
    private void attach(MovementEngine engine) {
        if (engine.getGoal() != goal) {
            engine.setGoal(goal);
        }
    }

    /** 输出全部按键状态 */
    private void logKeyStatus() {
        log.info("[Running] 按键状态: 前进={} 后退={} 左移={} 右移={} 疾跑={} 潜行={}",
                pressed(goal.forward()), pressed(goal.backward()), pressed(goal.left()),
                pressed(goal.right()), pressed(goal.sprint()), pressed(goal.sneak()));
    }

    private static String pressed(boolean value) {
        return value ? "按下" : "松开";
    }

    /** 解析 on/off 布尔参数；无法识别时返回 null */
    private static Boolean parseOnOff(String value) {
        return switch (value.toLowerCase()) {
            case "on", "true", "1" -> Boolean.TRUE;
            case "off", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** 可切换的"按住"按键 */
    private enum Key {
        FORWARD("前进"),
        BACKWARD("后退"),
        LEFT("左移"),
        RIGHT("右移"),
        SPRINT("疾跑"),
        SNEAK("潜行");

        private final String display;

        Key(String display) {
            this.display = display;
        }
    }

    private boolean isPressed(Key key) {
        return switch (key) {
            case FORWARD -> goal.forward();
            case BACKWARD -> goal.backward();
            case LEFT -> goal.left();
            case RIGHT -> goal.right();
            case SPRINT -> goal.sprint();
            case SNEAK -> goal.sneak();
        };
    }

    private void press(Key key, boolean value) {
        switch (key) {
            case FORWARD -> goal.setForward(value);
            case BACKWARD -> goal.setBackward(value);
            case LEFT -> goal.setLeft(value);
            case RIGHT -> goal.setRight(value);
            case SPRINT -> goal.setSprint(value);
            case SNEAK -> goal.setSneak(value);
        }
    }

    /** 按键开关子命令：forward / back / left / right / sprint / sneak */
    private final class KeyCommand extends CommandExecutor {
        private final Key key;

        KeyCommand(Key key) {
            this.key = key;
        }

        @Override
        public void onCommand(Command command, String label, String[] args) {
            MovementEngine engine = requireEngine();
            if (engine == null) return;

            boolean value;
            if (args.length == 0) {
                value = !isPressed(key);
            } else {
                Boolean parsed = parseOnOff(args[0]);
                if (parsed == null) {
                    log.info("[Running] 参数无效，用法: move {} [on|off]", label);
                    return;
                }
                value = parsed;
            }

            press(key, value);
            attach(engine);
            log.info("[Running] {} 已{}。", key.display, value ? "按下" : "松开");
            logKeyStatus();
        }

        @Override
        public List<String> onTabComplete(Command cmd, String label, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Stream.of("on", "off").filter(value -> value.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }

    /** 跳跃子命令：请求一次跳跃（可起跳时执行） */
    private final class JumpCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            MovementEngine engine = requireEngine();
            if (engine == null) return;

            boolean canJumpNow = engine.physics().canJump();
            goal.requestJump();
            attach(engine);
            if (canJumpNow) {
                log.info("[Running] 已请求跳跃。");
            } else {
                log.info("[Running] 已请求跳跃（将在可以起跳时执行）。");
            }
        }
    }

    /** 自动跳跃子命令：切换物理层的自动跳跃开关（前进遇可跳上的障碍自动起跳） */
    private final class AutoJumpCommand extends CommandExecutor {
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
                value = !physics.isAutoJumpEnabled();
            } else {
                Boolean parsed = parseOnOff(args[0]);
                if (parsed == null) {
                    log.info("[Running] 参数无效，用法: move {} [on|off]", label);
                    return;
                }
                value = parsed;
            }

            physics.setAutoJumpEnabled(value);
            log.info("[Running] 自动跳跃已{}（前进遇到可跳上的障碍时自动起跳）。", value ? "开启" : "关闭");
        }

        @Override
        public List<String> onTabComplete(Command cmd, String label, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Stream.of("on", "off").filter(value -> value.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }

    /** 停止子命令：复位全部按键并取消当前移动目标 */
    private final class StopCommand extends CommandExecutor {
        @Override
        public void onCommand(Command command, String label, String[] args) {
            Main plugin = Main.INSTANCE;
            if (plugin == null) {
                log.warn("[Running] 插件实例不存在。");
                return;
            }

            MovementEngine engine = plugin.movementEngine();
            goal.clear();
            if (engine != null) {
                engine.setGoal(null);
            }
            log.info("[Running] 已停止全部移动（按键已复位）。");
        }
    }
}
