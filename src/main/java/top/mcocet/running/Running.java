package top.mcocet.running;

import top.mcocet.running.commands.*;
import top.mcocet.running.movement.MovementAdapter;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.event.Listener;
import xin.bbtt.mcbot.plugin.Plugin;

public class Running implements Plugin, Listener {
    public static Running INSTANCE;
    private MovementAdapter movementAdapter;

    public Running() {
        INSTANCE = this;
    }

    @Override
    public String getName() {
        return ("Running");
    }

    @Override
    public String getVersion() {
        return ("1.0");
    }

    @Override
    public void onLoad() {
        getLogger().info("Running 插件已加载");
        
        try {
            movementAdapter = MovementAdapter.getInstance();
            getLogger().info("寻路系统初始化成功");
        } catch (Exception e) {
            getLogger().error("寻路系统初始化失败", e);
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("Running 插件已启动");
        
        // 只注册rung主命令
        Bot.Instance.getPluginManager().registerCommand(new RunCommand(), new RunCommandExecutor(), this);
        
        getLogger().info("Running命令帮助:");
        getLogger().info("- /rung goto <x> <z> or /rung goto <x> <y> <z>");
        getLogger().info("- /rung stop");
        getLogger().info("- /rung whereami");
        getLogger().info("- /rung help");
    }

    @Override
    public void onDisable() {
        getLogger().info("Running 插件已关闭");
        
        // 清理寻路系统资源
        if (movementAdapter != null) {
            // 可以在这里添加停止所有移动的逻辑
            // movementAdapter.cancelAllMovements();
        }
    }

    @Override
    public void onUnload() {
        getLogger().info("Running 插件已卸载");
    }
}
