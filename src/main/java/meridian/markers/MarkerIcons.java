package meridian.markers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.MapMarkers;

/**
 * The marker icon PNGs, as core reads them from the player's own game ({@link MapMarkers#iconImage})
 * - the module carries none. A server's own icon is the one the game downloaded for it, so custom
 * icons show too.
 *
 * <p>The settings liveList renders rows through JLabel HTML, which needs a URL: an icon shown there
 * is written once into the module's own data folder, on this machine, and the {@code <img>} tag
 * points at that copy. Unknown or missing icons resolve to an empty string and the row falls back
 * to the plain file name.
 */
final class MarkerIcons {

    private static volatile MapMarkers markers;
    private static volatile Path shown;

    /** markerImage file name → the file: URL of its copy ("" = not at hand). */
    private static final Map<String, String> URLS = new ConcurrentHashMap<>();

    private MarkerIcons() {
    }

    /** Where icons come from, and where the copies for the settings page go. */
    static void use(MapMarkers source, Path dataDir) {
        markers = source;
        shown = dataDir == null ? null : dataDir.resolve("icons");
        URLS.clear();
    }

    /**
     * A marker picture as bytes, for anything drawing a map of its own - the same art the settings
     * page shows inline, handed over whole: a map window cannot use an HTML tag.
     */
    static Optional<byte[]> bytes(String markerImage) {
        MapMarkers m = markers;
        if (m == null || !ours(markerImage)) {
            return Optional.empty();
        }
        return m.iconImage(markerImage);
    }

    /** A name we are willing to write a copy under: a plain file name, not a path elsewhere. */
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
        String url = URLS.computeIfAbsent(markerImage, MarkerIcons::copyFor);
        return url.isEmpty() ? ""
                : "<img src='" + url + "' width='" + px + "' height='" + px + "'>&nbsp;";
    }

    /** Writes the icon next to the module's data, once, and gives the copy's URL ("" without one). */
    private static String copyFor(String markerImage) {
        Path dir = shown;
        Optional<byte[]> bytes = bytes(markerImage);
        if (dir == null || bytes.isEmpty()) {
            return "";
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(markerImage);
            if (!Files.isRegularFile(file) || !Arrays.equals(Files.readAllBytes(file), bytes.get())) {
                Files.write(file, bytes.get());
            }
            return file.toUri().toURL().toExternalForm();
        } catch (IOException e) {
            return "";
        }
    }
}
