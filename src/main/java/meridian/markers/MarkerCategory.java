package meridian.markers;

/**
 * Coarse marker grouping used for the show/hide toggles.
 *
 * <p>Classification mirrors the server's own logic
 * ({@code MapMarkerUtils.isUserMarker} + id prefixes): a marker with a
 * {@code PlayerMarkerComponent} is a live player position; one with a
 * {@code PlacedByMarkerComponent} is a user marker whose id prefix
 * ({@code user_shared_} / {@code user_personal_}) splits shared from private;
 * everything else is a server/built-in marker (POI, spawn, home, death, warp).
 * {@code LOCAL} markers exist only on this proxy — the server never saw them.
 */
public enum MarkerCategory {
    PLAYER("player"),
    USER_SHARED("shared"),
    USER_PRIVATE("private"),
    SERVER("server"),
    LOCAL("local");

    private final String label;

    MarkerCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
