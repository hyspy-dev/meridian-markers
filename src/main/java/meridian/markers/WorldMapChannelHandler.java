package meridian.markers;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.ClearWorldMap;
import meridian.protocol.packets.worldmap.UpdateWorldMap;

/**
 * S2C NORMAL handler on the WorldMap channel. Every {@code UpdateWorldMap}
 * flows through the engine: markers are cached, hidden ones are stripped
 * (MODIFIED), and local/ghost markers are appended after a map reset. The
 * session it captures is the one valid pipe for forged marker frames — see
 * the per-stream note on {@link ProxySession}.
 */
final class WorldMapChannelHandler implements PacketHandler {

    private final MarkersEngine engine;

    WorldMapChannelHandler(MarkersEngine engine) {
        this.engine = engine;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof UpdateWorldMap m) {
            return engine.onWorldMapUpdate(m, session);
        }
        if (packet instanceof ClearWorldMap) {
            engine.onClearWorldMap();
        }
        return Action.FORWARD;
    }
}
