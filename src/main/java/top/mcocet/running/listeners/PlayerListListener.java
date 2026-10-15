package top.mcocet.running.listeners;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import top.mcocet.running.world.PlayerList;

/**
 * 玩家列表监听器：把 {@code ClientboundPlayerInfoUpdatePacket} 的 {@code ADD_PLAYER}
 * 动作翻译为 {@link PlayerList} 的 {@code UUID ↔ 用户名} 记录，供按用户名定位玩家
 * （如 {@code goto follow <name>}）；重新登录时清空（对齐玩家列表整体重建）。
 *
 * <p>线程：网络线程写入 {@link PlayerList}（并发容器），引擎 / 计算线程读取。</p>
 */
public final class PlayerListListener extends SessionAdapter {

    private final PlayerList playerList;

    public PlayerListListener(PlayerList playerList) {
        this.playerList = playerList;
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket update) {
            handleUpdate(update);
        } else if (packet instanceof ClientboundLoginPacket) {
            playerList.clear();
        }
    }

    private void handleUpdate(ClientboundPlayerInfoUpdatePacket update) {
        if (!update.getActions().contains(PlayerListEntryAction.ADD_PLAYER)) {
            return;
        }
        for (PlayerListEntry entry : update.getEntries()) {
            GameProfile profile = entry.getProfile();
            if (profile != null) {
                playerList.addPlayer(entry.getProfileId(), profile.getName());
            }
        }
    }
}
