package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import top.mcocet.running.world.InventoryState;

/**
 * 把服务器下发的背包/手持信息同步到 {@link InventoryState}（完整 36 格 + 盔甲 + 副手）。
 *
 * <p>处理：
 * <ul>
 *   <li>{@code SetPlayerInventory}：玩家容器单格全量更新（槽位 0..40）；</li>
 *   <li>{@code ContainerSetSlot}（containerId=0，即玩家背包窗口）：增量的格子变更，
 *       挖掘消耗、物品移动都会经此下发；</li>
 *   <li>{@code SetHeldSlot}：服务器设定的当前快捷栏手持槽。</li>
 * </ul></p>
 */
public final class InventoryListener extends SessionAdapter {

    /** 玩家背包容器 id（原版 window id 0 为玩家背包） */
    private static final int PLAYER_CONTAINER_ID = 0;

    private final InventoryState inventory;

    public InventoryListener(InventoryState inventory) {
        this.inventory = inventory;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundSetPlayerInventoryPacket inv) {
            inventory.setSlot(inv.getSlot(), inv.getContents());
        } else if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            if (slot.getContainerId() == PLAYER_CONTAINER_ID) {
                inventory.setSlot(slot.getSlot(), slot.getItem());
            }
        } else if (packet instanceof ClientboundSetHeldSlotPacket held) {
            inventory.setHeldSlot(held.getSlot());
        }
    }
}
