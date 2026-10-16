package top.mcocet.running.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.Main;
import top.mcocet.running.bot.BotState;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;

/**
 * pos 命令：显示机器人当前坐标、朝向与所在区块。
 */
public final class PositionCommand extends CommandExecutor {
    private final Logger log = LoggerFactory.getLogger(PositionCommand.class.getSimpleName());

    @Override
    public void onCommand(Command command, String label, String[] args) {
        Main plugin = Main.INSTANCE;
        if (plugin == null) {
            log.warn("[Running] 插件实例不存在。");
            return;
        }

        BotState state = plugin.botState();
        if (!state.hasPosition()) {
            log.info("[Running] 尚未收到服务器下发的初始坐标（未登录或世界尚未就绪）。");
            return;
        }

        double x = state.getX();
        double y = state.getY();
        double z = state.getZ();

        log.info("[Running] 坐标: X={} Y={} Z={}", fmt(x), fmt(y), fmt(z));
        log.info("[Running] 朝向: yaw={}° pitch={}° | 着地: {}",
                String.format("%.1f", state.getYaw()),
                String.format("%.1f", state.getPitch()),
                state.isOnGround() ? "是" : "否");
        log.info("[Running] 区块: ({}, {}) | 维度 {}，世界高度 [{}, {}) | 实体 ID={}",
                (int) Math.floor(x) >> 4,
                (int) Math.floor(z) >> 4,
                plugin.world().getDimension(),
                plugin.world().getMinY(),
                plugin.world().getMinY() + plugin.world().getHeight(),
                state.getEntityId());
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
