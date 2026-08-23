package meridian.markers;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The game's marker icon PNGs, bundled under {@code /mapmarkers/} in the module
 * jar. The settings liveList renders rows through JLabel HTML, so an
 * {@code <img src='jar:...'>} tag paints the real icon inline. Unknown
 * {@code markerImage} values (custom server icons) resolve to an empty string
 * and the row falls back to the plain file name.
 */
final class MarkerIcons {

    /** markerImage file name → resolved resource URL ("" = not bundled). */
    private static final Map<String, String> URLS = new ConcurrentHashMap<>();

    private MarkerIcons() {
    }

    /** HTML {@code <img>} tag for a marker image, or "" when unavailable. */
    static String imgTag(String markerImage, int px) {
        if (markerImage == null || markerImage.isEmpty() || "(none)".equals(markerImage)
                || markerImage.indexOf('/') >= 0 || markerImage.indexOf('\\') >= 0
                || markerImage.contains("..")) {
            return "";
        }
        String url = URLS.computeIfAbsent(markerImage, k -> {
            URL res = MarkerIcons.class.getResource("/mapmarkers/" + k);
            return res == null ? "" : res.toExternalForm();
        });
        return url.isEmpty() ? ""
                : "<img src='" + url + "' width='" + px + "' height='" + px + "'>&nbsp;";
    }
}
