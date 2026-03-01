package top.mcocet.running.commands;

import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mcocet.running.movement.PathExecutor;
import top.mcocet.running.pathfinding.goals.GoalBlock;
import top.mcocet.running.pathfinding.goals.GoalXZ;

import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.command.CommandExecutor;


public class RunCommandExecutor extends CommandExecutor {
    private static final Logger logger = LoggerFactory.getLogger("RunningCommandExecutor");
    private final PathExecutor pathExecutor = PathExecutor.getInstance();

    @Override
    public void onCommand(Command command, String label, String[] args) {
        if (args.length == 0) {
            showHelp();
            return;
        }
        
        String subCommand = args[0].toLowerCase();
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        
        switch (subCommand) {
            case "goto":
                handleGoto(subArgs);
                break;
            case "stop":
                handleStop(subArgs);
                break;
            case "whereami":
                handleWhereAmI(subArgs);
                break;
            case "help":
                showHelp();
                break;
            default:
                sendErrorMessage("Unknown subcommand: " + subCommand);
                showHelp();
                break;
        }
    }
    
    private void handleGoto(String[] args) {
        if (args.length < 2 || args.length > 3) {
            sendErrorMessage("Usage: rung goto <x> <z> or rung goto <x> <y> <z>");
            return;
        }
        
        try {
            if (args.length == 2) {
                // XZ坐标导航
                int x = Integer.parseInt(args[0]);
                int z = Integer.parseInt(args[1]);
                GoalXZ goal = new GoalXZ(x, z);

                logger.info("Finding path to XZ: {}, {}", x, z);
                sendInfoMessage("Searching path to " + x + ", " + z);
                
                pathExecutor.findAndExecutePath(goal)
                    .thenAccept(pathOpt -> {
                        if (pathOpt.isPresent()) {
                            sendSuccessMessage("Found path with " + pathOpt.get().size() + " nodes");
                        } else {
                            sendErrorMessage("No path found to target location");
                        }
                    })
                    .exceptionally(throwable -> {
                        String errorMsg = throwable.getMessage();
                        if (errorMsg == null) {
                            errorMsg = throwable.getClass().getSimpleName() + ": " + throwable.getCause();
                        }
                        
                        logger.error("路径查找异常详细信息:", throwable);
                        
                        if (errorMsg.contains("Already executing a path")) {
                            sendInfoMessage("Stopping current path and starting new navigation...");
                            // 重新尝试执行
                            try {
                                Thread.sleep(200);
                                pathExecutor.findAndExecutePath(goal)
                                    .thenAccept(retryPathOpt -> {
                                        if (retryPathOpt.isPresent()) {
                                            sendSuccessMessage("Found path with " + retryPathOpt.get().size() + " nodes");
                                        } else {
                                            sendErrorMessage("No path found to target location");
                                        }
                                    });
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                sendErrorMessage("Navigation interrupted");
                            }
                        } else if (errorMsg.contains("Cannot invoke") && errorMsg.contains("because \"v\" is null")) {
                            sendErrorMessage("路径规划内部错误：空指针异常，请检查目标坐标是否正确");
                        } else if (errorMsg.contains("启发式值异常大")) {
                            sendErrorMessage("路径规划错误：启发式值异常，请检查目标位置合理性");
                        } else if (errorMsg.contains("Movement系统不可用")) {
                            sendErrorMessage("Movement系统未准备就绪，请稍后再试");
                        } else if (errorMsg.contains("当前位置为(0,0,0)")) {
                            sendErrorMessage("机器人位置信息尚未同步，请稍等片刻或重新连接服务器");
                        } else {
                            sendErrorMessage("Error finding path: " + errorMsg);
                        }
                        return null;
                    });
                    
            } else if (args.length == 3) {
                // XYZ坐标导航
                int x = Integer.parseInt(args[0]);
                int y = Integer.parseInt(args[1]);
                int z = Integer.parseInt(args[2]);
                GoalBlock goal = new GoalBlock(x, y, z);
                
                logger.info("Finding path to XYZ: {}, {}, {}", x, y, z);
                sendInfoMessage("Searching path to " + x + ", " + y + ", " + z);
                
                pathExecutor.findAndExecutePath(goal)
                    .thenAccept(pathOpt -> {
                        if (pathOpt.isPresent()) {
                            sendSuccessMessage("Found path with " + pathOpt.get().size() + " nodes");
                        } else {
                            sendErrorMessage("No path found to target location");
                        }
                    })
                    .exceptionally(throwable -> {
                        String errorMsg = throwable.getMessage();
                        if (errorMsg == null) {
                            errorMsg = throwable.getClass().getSimpleName() + ": " + throwable.getCause();
                        }
                        
                        logger.error("路径查找异常详细信息:", throwable);
                        
                        if (errorMsg.contains("Already executing a path")) {
                            sendInfoMessage("Stopping current path and starting new navigation...");
                            // 重新尝试执行
                            try {
                                Thread.sleep(200);
                                pathExecutor.findAndExecutePath(goal)
                                    .thenAccept(retryPathOpt -> {
                                        if (retryPathOpt.isPresent()) {
                                            sendSuccessMessage("Found path with " + retryPathOpt.get().size() + " nodes");
                                        } else {
                                            sendErrorMessage("No path found to target location");
                                        }
                                    });
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                sendErrorMessage("Navigation interrupted");
                            }
                        } else if (errorMsg.contains("Cannot invoke") && errorMsg.contains("because \"v\" is null")) {
                            sendErrorMessage("路径规划内部错误：空指针异常，请检查目标坐标是否正确");
                        } else if (errorMsg.contains("启发式值异常大")) {
                            sendErrorMessage("路径规划错误：启发式值异常，请检查目标位置合理性");
                        } else if (errorMsg.contains("Movement系统不可用")) {
                            sendErrorMessage("Movement系统未准备就绪，请稍后再试");
                        } else if (errorMsg.contains("当前位置为(0,0,0)")) {
                            sendErrorMessage("机器人位置信息尚未同步，请稍等片刻或重新连接服务器");
                        } else {
                            sendErrorMessage("Error finding path: " + errorMsg);
                        }
                        return null;
                    });
            }
            
        } catch (NumberFormatException e) {
            sendErrorMessage("Invalid coordinates. Please use numbers only.");
        }
    }
    
    private void handleStop(String[] args) {
        if (pathExecutor.isExecuting()) {
            pathExecutor.stopExecution();
            sendSuccessMessage("Stopped path execution");
        } else {
            sendInfoMessage("No path is currently executing");
        }
    }
    
    private void handleWhereAmI(String[] args) {
        // 这里可以调用位置查询逻辑
        sendInfoMessage("Position query functionality will be implemented");
    }
    
    private void showHelp() {
        sendInfoMessage("=== Running Plugin Commands ===");
        sendInfoMessage("/rung goto <x> <z> - Navigate to XZ coordinates");
        sendInfoMessage("/rung goto <x> <y> <z> - Navigate to exact XYZ position");
        sendInfoMessage("/rung stop - Stop current path execution");
        sendInfoMessage("/rung whereami - Show current position");
        sendInfoMessage("/rung help - Show this help message");
    }
    
    private void sendInfoMessage(String message) {
        logger.info("[Running] " + message);
    }
    
    private void sendSuccessMessage(String message) {
        logger.info("[Running] ✓ " + message);
    }
    
    private void sendErrorMessage(String message) {
        logger.error("[Running] ✗ " + message);
    }
}