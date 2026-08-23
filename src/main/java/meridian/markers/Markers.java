package meridian.markers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import meridian.protocol.Color;
import meridian.protocol.Direction;
import meridian.protocol.FormattedMessage;
import meridian.protocol.Position;
import meridian.protocol.Transform;
import meridian.protocol.packets.worldmap.MapMarker;
import meridian.protocol.packets.worldmap.MapMarkerComponent;
import meridian.protocol.packets.worldmap.PlacedByMarkerComponent;
import meridian.protocol.packets.worldmap.PlayerMarkerComponent;
import meridian.protocol.packets.worldmap.TintComponent;

/**
 * Wire-format helpers: classify incoming {@link MapMarker}s the same way the
 * server does, and build forged markers for the local / last-seen features.
 *
 * <p>Server id conventions (from the Hytale server source):
 * {@code user_shared_<uuid>} / {@code user_personal_<uuid>} for user markers,
 * {@code Player-<uuid>} for player positions, {@code death-marker-<uuid>},
 * {@code Warp-<id>}, {@code Spawn}, {@code Home<n>}, {@code UniquePrefab-...},
 * {@code prefab-<uuid>} and bare UUIDs for block-placed markers. Our forged ids
 * use the {@code proxy_} prefix, which no server path ever generates.
 */
final class Markers {

    static final String LOCAL_PREFIX = "proxy_marker_";
    static final String GHOST_PREFIX = "proxy_lastseen_";
    /** Ships with the game; the server's own fallback ("User1.png") does not. */
    static final String DEFAULT_USER_IMAGE = "UserA.png";

    private static final DateTimeFormatter SEEN_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private Markers() {
    }

    static boolean isProxyId(String id) {
        return id != null && id.startsWith("proxy_");
    }

    static boolean isGhostId(String id) {
        return id != null && id.startsWith(GHOST_PREFIX);
    }

    static String newLocalId() {
        return LOCAL_PREFIX + UUID.randomUUID();
    }

    static String ghostId(UUID playerId) {
        return GHOST_PREFIX + playerId;
    }

    /** Fills a fresh record from a wire marker; classification mirrors the server. */
    static MarkerRecord toRecord(String worldId, MapMarker m) {
        MarkerRecord r = new MarkerRecord();
        r.id = m.id;
        r.worldId = worldId;
        r.name = text(m.name);
        r.markerImage = m.markerImage;
        if (m.transform != null && m.transform.position != null) {
            r.x = m.transform.position.x;
            r.y = m.transform.position.y;
            r.z = m.transform.position.z;
        }
        r.lastSeenMs = System.currentTimeMillis();
        r.online = true; // arriving on the wire = currently emitted by the server
        if (m.components != null) {
            for (MapMarkerComponent c : m.components) {
                if (c instanceof PlayerMarkerComponent p) {
                    r.playerId = p.playerId;
                } else if (c instanceof PlacedByMarkerComponent p) {
                    r.placedById = p.playerId;
                    r.placedByName = text(p.name);
                } else if (c instanceof TintComponent t) {
                    r.tintRgb = rgb(t.color);
                }
            }
        }
        if (r.playerId != null) {
            r.category = MarkerCategory.PLAYER;
            if (r.name.isEmpty()) {
                r.name = "player " + shortId(r.playerId);
            }
        } else if (isProxyId(m.id)) {
            r.category = MarkerCategory.LOCAL;
        } else if (r.placedById != null || r.placedByName != null) {
            r.category = m.id != null && m.id.startsWith("user_shared_")
                    ? MarkerCategory.USER_SHARED
                    : MarkerCategory.USER_PRIVATE;
        } else {
            r.category = MarkerCategory.SERVER;
        }
        return r;
    }

    /** Rebuilds a wire marker from a snapshot — for LOCAL markers and ghosts. */
    static MapMarker build(String id, String name, String image, double x, double y, double z,
                           int tintRgb, UUID placedById, String placedByName) {
        MapMarker m = new MapMarker();
        m.id = id;
        m.name = name == null || name.isEmpty() ? null : raw(name);
        m.markerImage = image == null || image.isEmpty() ? DEFAULT_USER_IMAGE : image;
        m.transform = new Transform(new Position(x, y, z), new Direction());
        List<MapMarkerComponent> components = new ArrayList<>(2);
        if (tintRgb >= 0) {
            components.add(new TintComponent(color(tintRgb)));
        }
        if (placedById != null) {
            components.add(new PlacedByMarkerComponent(
                    raw(placedByName == null ? "" : placedByName), placedById));
        }
        m.components = components.isEmpty() ? null : components.toArray(MapMarkerComponent[]::new);
        return m;
    }

    /** Ghost marker shown at a player's last known position. */
    static MapMarker buildGhost(MarkerRecord player) {
        String seen = SEEN_FMT.format(Instant.ofEpochMilli(player.lastSeenMs)
                .atZone(ZoneId.systemDefault()));
        return build(ghostId(player.playerId),
                player.name + " (seen " + seen + ")",
                player.markerImage == null || player.markerImage.isEmpty()
                        ? "Player.png" : player.markerImage,
                player.x, player.y, player.z,
                player.tintRgb, null, null);
    }

    static MapMarker buildLocal(MarkerRecord r) {
        return build(r.id, r.name, r.markerImage, r.x, r.y, r.z,
                r.tintRgb, r.placedById, r.placedByName);
    }

    static FormattedMessage raw(String text) {
        FormattedMessage m = new FormattedMessage();
        m.rawText = text;
        return m;
    }

    /** Best-effort plain text: rawText, else the translation key's last segment. */
    static String text(FormattedMessage m) {
        if (m == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collect(m, sb);
        return sb.toString().trim();
    }

    private static void collect(FormattedMessage m, StringBuilder sb) {
        if (m.rawText != null && !m.rawText.isEmpty()) {
            sb.append(m.rawText);
        } else if (m.messageId != null && !m.messageId.isEmpty()) {
            int dot = m.messageId.lastIndexOf('.');
            sb.append(dot >= 0 ? m.messageId.substring(dot + 1) : m.messageId);
        }
        if (m.children != null) {
            for (FormattedMessage child : m.children) {
                if (child != null) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    collect(child, sb);
                }
            }
        }
    }

    static int rgb(Color c) {
        return c == null ? -1
                : ((c.red & 0xFF) << 16) | ((c.green & 0xFF) << 8) | (c.blue & 0xFF);
    }

    static Color color(int rgb) {
        return new Color((byte) ((rgb >> 16) & 0xFF), (byte) ((rgb >> 8) & 0xFF), (byte) (rgb & 0xFF));
    }

    static String shortId(UUID id) {
        String s = id.toString();
        return s.substring(0, Math.min(8, s.length()));
    }
}
