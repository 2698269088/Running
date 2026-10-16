package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.MovementEngine;
import top.mcocet.running.bot.PlayerPhysics;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

/**
 * look 命令：调整机器人视角（yaw 偏航角 / pitch 俯仰角，角度制）。
 *
 * <p>带 {@code ~} 前缀的参数表示相对当前视角旋转（如 {@code look ~90 ~} 表示
 * 左转 90 度；单独 {@code ~} 表示该轴不变）；yaw 归一化到 [-180, 180)，
 * pitch 由物理层夹取到 [-90, 90]；新视角由下一次位置包同步到服务器。</p>
 */
public final class LookCommand extends CommandExecutor {
    private final Logger log = LoggerFactory.getLogger(LookCommand.class.getSimpleName());

    @Override
    public void onCommand(Command command, String label, String[] args) {
        MovementEngine engine = requireEngine();
        if (engine == null) {
            return;
        }

        PlayerPhysics physics = engine.physics();
        if (args.length == 0) {
            log.info("[Running] 当前视角: yaw={}°（偏航） pitch={}°（俯仰）",
                    fmt(physics.yaw()), fmt(physics.pitch()));
            return;
        }
        if (args.length != 2) {
            log.info("[Running] 用法: {}", command.getUsage());
            return;
        }

        float yaw;
        float pitch;
        try {
            yaw = wrapDegrees(resolve(args[0], physics.yaw()));
            pitch = resolve(args[1], physics.pitch());
        } catch (NumberFormatException e) {
            log.info("[Running] 角度格式错误（数字，可加 ~ 前缀表示相对），用法: {}", command.getUsage());
            return;
        }

        engine.look(yaw, pitch);
        log.info("[Running] 视角已调整: yaw={}°（偏航） pitch={}°（俯仰）",
                fmt(physics.yaw()), fmt(physics.pitch()));
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
            log.warn("[Running] 尚未进入游戏（未连接服务器或未登录），无法调整视角。");
            return null;
        }
        return engine;
    }

    /** 解析角度参数：{@code ~} 前缀为相对当前值的增量（单独 {@code ~} 表示不变），否则为绝对值 */
    private static float resolve(String arg, float current) {
        if (arg.startsWith("~")) {
            String offset = arg.substring(1);
            return offset.isEmpty() ? current : current + Float.parseFloat(offset);
        }
        return Float.parseFloat(arg);
    }

    /** 归一化 yaw 到 [-180, 180)（对齐 Mth.wrapDegrees） */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static String fmt(float value) {
        return String.format("%.1f", value);
    }
}
