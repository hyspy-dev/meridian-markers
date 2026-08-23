package meridian.markers;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.JoinWorld;
import meridian.protocol.packets.player.RemoveMapMarker;
import meridian.protocol.packets.player.SetClientId;
import meridian.protocol.packets.worldmap.CreateUserMarker;
import meridian.protocol.packets.worldmap.TeleportToWorldMapMarker;

/**
 * BOTH-direction NORMAL handler on the Default channel.
 *
 * <p>C2S: intercepts the marker requests — in local-only mode (or for ids the
 * server never knew) they are answered locally and DROPped; otherwise they are
 * forwarded with a refusal timeout armed. S2C: tracks the current world and
 * pins the Default-channel session for chat notifications.
 */
final class DefaultChannelHandler implements PacketHandler {

    private final MarkersEngine engine;

    DefaultChannelHandler(MarkersEngine engine) {
        this.engine = engine;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof ClientMovement movement) {
            engine.onClientMovement(movement);
            return Action.FORWARD;
        }
        if (packet instanceof CreateUserMarker create) {
            return engine.onCreateUserMarker(create, session);
        }
        if (packet instanceof RemoveMapMarker remove) {
            return engine.onRemoveMapMarker(remove, session);
        }
        if (packet instanceof TeleportToWorldMapMarker teleport) {
            return engine.onTeleportToMarker(teleport, session);
        }
        return Action.FORWARD;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof JoinWorld join) {
            engine.onJoinWorld(join.worldUuid, session);
        } else if (packet instanceof SetClientId) {
            engine.chat.bind(session);
        }
        return Action.FORWARD;
    }
}
