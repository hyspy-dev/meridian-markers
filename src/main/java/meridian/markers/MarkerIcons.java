package meridian.markers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
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

    /**
     * A marker picture as bytes, for anything drawing a map of its own.
     *
     * <p>The same art the settings page shows inline, handed over whole: a map window cannot use
     * an HTML tag, and these are the only copies of the game's marker icons we ship.
     */
    static Optional<byte[]> bytes(String markerImage) {
        if (!ours(markerImage)) {
            return Optional.empty();
        }
        try (InputStream in = MarkerIcons.class.getResourceAsStream("/mapmarkers/" + markerImage)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** A name we are willing to look up: one of ours, and not a path out of the jar. */
    private static boolean ours(String markerImage) {
        return markerImage != null && !markerImage.isEmpty() && !"(none)".equals(markerImage)
                && markerImage.indexOf('/') < 0 && markerImage.indexOf('\\') < 0
                && !markerImage.contains("..");
    }

    /** HTML {@code <img>} tag for a marker image, or "" when unavailable. */
    static String imgTag(String markerImage, int px) {
        if (!ours(markerImage)) {
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
