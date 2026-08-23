package meridian.markers;

/**
 * Where a marker created from the plugin form should live: proxy-only, or on
 * the server as a personal / shared user marker (with the usual refusal
 * fallback to local).
 */
public enum CreateTarget {
    LOCAL,
    PRIVATE,
    SHARED
}
