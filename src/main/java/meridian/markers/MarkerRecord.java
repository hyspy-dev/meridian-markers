package meridian.markers;

import java.util.UUID;
import meridian.protocol.packets.worldmap.MapMarker;

/**
 * One cached marker. Serialized to {@code markers.json} by Gson — every
 * non-transient field must stay JSON-friendly. The {@code live} wire object is
 * session-only: it is the last {@link MapMarker} the server sent (cloned), used
 * to re-show a hidden marker without re-encoding it from the snapshot.
 */
public final class MarkerRecord {

    public String id = "";
    /** World the marker belongs to — JoinWorld's worldUuid as a string. */
    public String worldId = "";
    /** Plain-text name extracted from the FormattedMessage (best effort). */
    public String name = "";
    public String markerImage = "";
    public double x;
    public double y;
    public double z;
    /** Packed 0xRRGGBB tint, or {@code -1} when the marker has no TintComponent. */
    public int tintRgb = -1;
    public MarkerCategory category = MarkerCategory.SERVER;
    /** Creator of a user marker (PlacedByMarkerComponent), if any. */
    public UUID placedById;
    public String placedByName;
    /** Subject of a player marker (PlayerMarkerComponent), if any. */
    public UUID playerId;
    /** Last time the server sent/updated this marker (epoch ms). */
    public long lastSeenMs;
    /** Players only: whether the server is currently emitting this marker. */
    public boolean online;
    /**
     * Set when the server refused a delete and we removed the marker locally
     * instead — future server re-adds of this id are stripped so it stays gone.
     */
    public boolean locallyRemoved;

    /** Last wire marker as received (clone) — never persisted. */
    public transient MapMarker live;

    /** Group key for the icon toggle. */
    public String iconKey() {
        return markerImage == null || markerImage.isEmpty() ? "(none)" : markerImage;
    }

    /** Group key for the colour toggle. */
    public String colorKey() {
        return tintRgb < 0 ? "(none)" : String.format("#%06X", tintRgb & 0xFFFFFF);
    }
}
