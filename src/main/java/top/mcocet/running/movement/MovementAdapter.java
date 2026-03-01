package top.mcocet.running.movement;

import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.MovementSync;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.Server;
import xin.bbtt.movements.WalkMovement;

import java.util.concurrent.CompletableFuture;

public class MovementAdapter {
    private static MovementAdapter instance;
    private static final Logger logger = LoggerFactory.getLogger("RunningMovementAdapterSystem");

    private MovementAdapter() {
        // 不再需要存储MovementSync实例，直接使用MovementSync.Instance
        // 检查MovementSync插件是否加载
        if (!Bot.Instance.getPluginManager().isPluginLoaded("MovementSync")) {
            throw new IllegalStateException("MovementSync plugin is not loaded!");
        }
        
        // 验证MovementSync.Instance是否存在
        if (MovementSync.Instance == null) {
            throw new IllegalStateException("MovementSync.Instance is null!");
        }
    }
    
    public static MovementAdapter getInstance() {
        if (instance == null) {
            instance = new MovementAdapter();
        }
        return instance;
    }
    
    public boolean isAvailable() {
        boolean movementSyncOk = MovementSync.Instance != null && MovementSync.Instance.entityId != -1;
        boolean botRunning = Bot.Instance.isRunning();
        boolean correctServer = Bot.Instance.getServer() == Server.Xin;
        
        if (!movementSyncOk) {
            logger.info("DEBUG: MovementSync.Instance为空或entityId为-1");
            if (MovementSync.Instance != null) {
                logger.info("DEBUG: MovementSync.Instance.entityId = " + MovementSync.Instance.entityId);
            }
        }
        if (!botRunning) {
            logger.info("DEBUG: Bot未运行");
        }
        if (!correctServer) {
            logger.info("DEBUG: 服务器不匹配，当前服务器: " + Bot.Instance.getServer());
        }
        
        return movementSyncOk && botRunning && correctServer;
    }
    
    public CompletableFuture<Void> moveTo(Vector3d target) {
        return CompletableFuture.runAsync(() -> {
            if (!isAvailable()) {
                throw new IllegalStateException("Movement system is not available");
            }
            
            MovementSync.Instance.getMovementController().addMovement(new WalkMovement(
                target.sub(MovementSync.Instance.position.get()).normalize().mul(0.2159), 
                1000L
            ));
        });
    }
    
    public CompletableFuture<Void> jump() {
        return CompletableFuture.runAsync(() -> {
            if (!isAvailable()) {
                throw new IllegalStateException("Movement system is not available");
            }
            
            MovementSync.Instance.jump();
        });
    }
    
    public CompletableFuture<Void> lookAt(Vector3d target) {
        return CompletableFuture.runAsync(() -> {
            if (!isAvailable()) {
                throw new IllegalStateException("Movement system is not available");
            }
            
            MovementSync.Instance.lookAt(target);
        });
    }
    
    public Vector3d getCurrentPosition() {
        // 完全模拟whereami命令的行为，直接使用MovementSync.Instance
        if (MovementSync.Instance == null) {
            return new Vector3d(0, 0, 0);
        }
        
        return new Vector3d(MovementSync.Instance.position.get());
    }
    
    // 移除了syncPositionIfNeeded方法，因为我们现在直接使用MovementSync.Instance
    
    public Vector3d getHeadPosition() {
        if (!isAvailable()) {
            return new Vector3d(0, 1.62, 0);
        }
        return MovementSync.Instance.getHeadPosition();
    }
    
    public int getEntityId() {
        if (MovementSync.Instance == null) {
            return -1;
        }
        return MovementSync.Instance.entityId;
    }
    
    public boolean isOnGround() {
        if (!isAvailable()) {
            return true;
        }
        return MovementSync.Instance.onGround.get();
    }
    
    public void cancelAllMovements() {
        if (isAvailable()) {
            MovementSync.Instance.getMovementController().cancelAll();
        }
    }
}