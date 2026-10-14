package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import top.mcocet.running.world.item.ItemRegistry;

/**
 * 拦截配置阶段下发的 {@code ClientboundRegistryDataPacket}，把 {@code minecraft:item} /
 * {@code minecraft:block} 注册表喂给 {@link ItemRegistry}，用于解析物品的
 * {@code minecraft:tool} 组件（挖掘速度/正确工具判定）。
 *
 * <p>注册表在配置阶段一次性下发。若监听器注册晚于该包（例如插件在连接完成后才启用），
 * 将无法拿到工具表——运行日志会提示"物品注册表已解析"，缺失时需确认启用时机。</p>
 */
public final class RegistryListener extends SessionAdapter {

    private final ItemRegistry itemRegistry;

    public RegistryListener(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundRegistryDataPacket registryData) {
            itemRegistry.apply(registryData);
        }
    }
}
