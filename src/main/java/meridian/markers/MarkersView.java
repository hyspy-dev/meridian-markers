package meridian.markers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import meridian.core.api.Chat;
import meridian.core.api.MapMarkers;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.Vec3;
import meridian.core.api.World;

/**
 * The marker page: what is on the map, and what the player wants to see of it.
 *
 * <p>All the difficult parts - remembering markers, forging them, coping with a server that
 * answers a refusal with silence - belong to core. What is left here is the part a person
 * actually touches: a list they can search and click, groups by icon and colour, and a form for
 * placing a new one.
 *
 * <p>Hiding works by kind, by icon, by colour and by marker. They combine as you would expect:
 * anything switched off anywhere is off. Switching a whole kind back on brings back every marker
 * in it, including ones clicked away one at a time - "show players again" means all of them.
 */
final class MarkersView {

    private static final DateTimeFormatter SEEN = DateTimeFormatter.ofPattern("HH:mm");
    /** Rows are drawn as HTML on a dark background, so colour comes from font tags. */
    private static final String DIM = "#8A8A8A";
    private static final String ON = "<font color='#7FD37F'>[x]</font> ";
    private static final String OFF = "<font color='#666666'>[&nbsp;]</font> ";

    private final MapMarkers markers;
    private final Chat chat;
    private final World world;

    // What the player has switched off. Kept here because it is a view preference, not a fact
    // about the markers; core is told the result of it.
    private final Set<MarkerCategory> hiddenKinds = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenIcons = ConcurrentHashMap.newKeySet();
    private final Set<String> hiddenColours = ConcurrentHashMap.newKeySet();

    private volatile List<String> markerRows = List.of();
    private volatile List<String> markerKeys = List.of();
    private volatile List<String> groupRows = List.of();
    private volatile List<String> groupKeys = List.of();
    private volatile String status = "No markers yet.";
    private volatile String search = "";
    private volatile String selectedId;
    private volatile String createStatus = "";

    MarkersView(MapMarkers markers, Chat chat, World world) {
        this.markers = markers;
        this.chat = chat;
        this.world = world;
    }

    // ------------------------------------------------------------------
    // Applying what the player asked for
    // ------------------------------------------------------------------

    /**
     * Brings the map in line with the switches, and redraws the rows.
     *
     * <p>Run on a timer rather than the moment a marker arrives: markers land several times a
     * second while walking, and each burst is worth one decision, not a hundred.
     */
    void refresh() {
        List<Marker> all = markers.all();
        Set<String> hide = new HashSet<>();
        Set<String> show = new HashSet<>();
        for (Marker marker : all) {
            if (switchedOff(marker)) {
                hide.add(marker.id());
            } else if (markers.isHidden(marker.id())) {
                show.add(marker.id());
            }
        }
        if (!hide.isEmpty()) {
            markers.hide(hide);
        }
        if (!show.isEmpty()) {
            markers.show(show);
        }
        rebuild(all);
    }

    private boolean switchedOff(Marker marker) {
        return hiddenKinds.contains(marker.category())
                || hiddenIcons.contains(iconKey(marker))
                || hiddenColours.contains(colourKey(marker));
    }

    void setKindShown(MarkerCategory category, boolean shown) {
        if (shown) {
            hiddenKinds.remove(category);
        } else {
            hiddenKinds.add(category);
        }
        refresh();
    }

    void showEverything() {
        hiddenKinds.clear();
        hiddenIcons.clear();
        hiddenColours.clear();
        markers.showAll();
        refresh();
    }

    // ------------------------------------------------------------------
    // Clicks
    // ------------------------------------------------------------------

    void onMarkerClick(int index) {
        List<String> keys = markerKeys;
        if (index < 0 || index >= keys.size()) {
            return;
        }
        String id = keys.get(index);
        selectedId = id;
        if (markers.isHidden(id)) {
            markers.show(id);
        } else {
            markers.hide(id);
        }
        refresh();
    }

    void onGroupClick(int index) {
        List<String> keys = groupKeys;
        if (index < 0 || index >= keys.size()) {
            return;
        }
        String key = keys.get(index);
        if (key.startsWith("icon:")) {
            toggle(hiddenIcons, key.substring("icon:".length()));
        } else if (key.startsWith("colour:")) {
            toggle(hiddenColours, key.substring("colour:".length()));
        }
        refresh();
    }

    private static void toggle(Set<String> set, String key) {
        if (!set.remove(key)) {
            set.add(key);
        }
    }

    /** Deletes the marker whose row was last clicked. */
    void deleteSelected() {
        String id = selectedId;
        if (id == null) {
            return;
        }
        Marker marker = markers.get(id).orElse(null);
        if (marker == null) {
            return;
        }
        selectedId = null;
        markers.remove(id);
        chat.send("[Markers] Removed '" + marker.displayName() + "'.");
        refresh();
    }

    /** Forgets where players were last seen, keeping the ones still online. */
    void forgetOfflinePlayers() {
        for (Marker marker : markers.byCategory(MarkerCategory.PLAYER)) {
            if (!marker.online()) {
                markers.remove(marker.id());
            }
        }
        refresh();
    }

    /** Deletes every marker of our own in this world. */
    void deleteLocal() {
        for (Marker marker : markers.byCategory(MarkerCategory.LOCAL)) {
            markers.remove(marker.id());
        }
        refresh();
    }

    // ------------------------------------------------------------------
    // The create form
    // ------------------------------------------------------------------

    /** The player's position, for filling the form in. Null before they have moved. */
    Vec3 here() {
        return world.player().map(meridian.core.api.Player::position).orElse(null);
    }

    void create(String name, String xText, String zText, MarkerIcon icon,
                int tintArgb, boolean applyTint, CreateTarget target) {
        double x;
        double z;
        try {
            x = Double.parseDouble(xText.trim());
            z = Double.parseDouble(zText.trim());
        } catch (RuntimeException e) {
            createStatus = "X and Z have to be numbers.";
            return;
        }
        String trimmed = name == null ? "" : name.trim();
        int colour = applyTint ? tintArgb & 0xFFFFFF : -1;
        Vec3 at = new Vec3(x, 100, z);

        if (target == CreateTarget.LOCAL || markers.localOnly()) {
            Marker made = markers.createLocal(trimmed, at, icon.file(), colour);
            createStatus = "'" + made.displayName() + "' saved on your map at ("
                    + (int) x + ", " + (int) z + ")."
                    + (target == CreateTarget.LOCAL ? "" : " (this map only)");
            refresh();
            return;
        }
        if (trimmed.length() > 24) {
            createStatus = "The server will not take a name longer than 24 characters.";
            return;
        }
        createStatus = "Asked the server for a marker at (" + (int) x + ", " + (int) z
                + ") - if it will not, you get a local one.";
        markers.create(trimmed, at, icon.file(), colour, target == CreateTarget.SHARED)
                .thenAccept(made -> {
                    createStatus = made.category() == MarkerCategory.LOCAL
                            ? "The server would not take '" + made.displayName()
                                    + "' - saved on your map instead."
                            : "The server placed '" + made.displayName() + "'.";
                    refresh();
                });
    }

    String createStatus() {
        return createStatus;
    }

    void setCreateStatus(String text) {
        createStatus = text;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    List<String> markerRows() {
        return markerRows;
    }

    List<String> groupRows() {
        return groupRows;
    }

    String status() {
        return status;
    }

    void setSearch(String filter) {
        search = filter == null ? "" : filter.trim().toLowerCase();
        refresh();
    }

    String selectedLine() {
        String id = selectedId;
        if (id == null) {
            return "-";
        }
        return markers.get(id)
                .map(m -> m.displayName() + "  (" + (int) m.position().x() + ", "
                        + (int) m.position().z() + ")  " + m.category().label())
                .orElse("- (that marker is gone)");
    }

    private void rebuild(List<Marker> all) {
        List<Marker> sorted = new ArrayList<>(all);
        sorted.sort(Comparator
                .comparingInt((Marker m) -> m.category().ordinal())
                .thenComparing(Marker::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Marker::id));

        List<String> rows = new ArrayList<>(sorted.size());
        List<String> keys = new ArrayList<>(sorted.size());
        Map<String, Integer> icons = new TreeMap<>();
        Map<String, Integer> colours = new TreeMap<>();
        int shown = 0;
        int online = 0;
        int remembered = 0;

        for (Marker marker : sorted) {
            icons.merge(iconKey(marker), 1, Integer::sum);
            colours.merge(colourKey(marker), 1, Integer::sum);
            boolean visible = !markers.isHidden(marker.id());
            if (visible) {
                shown++;
            }
            if (marker.category() == MarkerCategory.PLAYER) {
                if (marker.online()) {
                    online++;
                } else {
                    remembered++;
                }
            }
            if (!matches(marker)) {
                continue;
            }
            rows.add(row(marker, visible));
            keys.add(marker.id());
        }
        markerRows = List.copyOf(rows);
        markerKeys = List.copyOf(keys);

        List<String> groups = new ArrayList<>();
        List<String> groupIds = new ArrayList<>();
        for (Map.Entry<String, Integer> icon : icons.entrySet()) {
            groups.add("<html>" + (hiddenIcons.contains(icon.getKey()) ? OFF : ON) + "icon: "
                    + MarkerIcons.imgTag(icon.getKey(), 14) + escape(icon.getKey())
                    + ' ' + span(DIM, "(" + icon.getValue() + ")"));
            groupIds.add("icon:" + icon.getKey());
        }
        for (Map.Entry<String, Integer> colour : colours.entrySet()) {
            String key = colour.getKey();
            String swatch = "(none)".equals(key)
                    ? span(DIM, key)
                    : span(bright(Integer.parseInt(key.substring(1), 16)), "■ " + key);
            groups.add("<html>" + (hiddenColours.contains(key) ? OFF : ON) + "colour: "
                    + swatch + ' ' + span(DIM, "(" + colour.getValue() + ")"));
            groupIds.add("colour:" + key);
        }
        groupRows = List.copyOf(groups);
        groupKeys = List.copyOf(groupIds);

        String filtered = search.isEmpty() ? "" : ", " + keys.size() + " matching";
        status = sorted.size() + " known, " + shown + " on the map, players "
                + online + " online / " + remembered + " remembered" + filtered;
    }

    private String row(Marker marker, boolean visible) {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(visible ? ON : OFF);
        String img = MarkerIcons.imgTag(iconKey(marker), 14);
        sb.append(img);
        if (marker.colourRgb() >= 0) {
            sb.append(span(bright(marker.colourRgb()), "■")).append(' ');
        }
        String name = escape(marker.displayName());
        sb.append(visible ? name : span("#777777", name));
        sb.append(' ').append(span(DIM, "(" + (int) marker.position().x() + ", "
                + (int) marker.position().z() + ")"));
        sb.append(' ').append(span(kindColour(marker.category()), marker.category().label()));
        if (img.isEmpty() && !"(none)".equals(iconKey(marker))) {
            sb.append(' ').append(span(DIM, escape(iconKey(marker))));
        }
        if (marker.category() == MarkerCategory.PLAYER) {
            sb.append(' ').append(marker.online()
                    ? span("#7FD37F", "online")
                    : span(DIM, "seen " + SEEN.format(Instant.ofEpochMilli(marker.lastSeenMillis())
                            .atZone(ZoneId.systemDefault()))));
        } else if (!marker.online() && marker.category() != MarkerCategory.LOCAL) {
            sb.append(' ').append(span(DIM, "[remembered]"));
        }
        return sb.toString();
    }

    private boolean matches(Marker marker) {
        String filter = search;
        if (filter.isEmpty()) {
            return true;
        }
        String haystack = (marker.displayName() + ' ' + marker.id() + ' ' + iconKey(marker)
                + ' ' + colourKey(marker) + ' ' + marker.category().label()
                + (marker.category() == MarkerCategory.PLAYER
                        ? (marker.online() ? " online" : " offline") : ""))
                .toLowerCase();
        return haystack.contains(filter);
    }

    private static String iconKey(Marker marker) {
        return marker.icon() == null || marker.icon().isEmpty() ? "(none)" : marker.icon();
    }

    private static String colourKey(Marker marker) {
        return marker.colourRgb() < 0 ? "(none)"
                : String.format("#%06X", marker.colourRgb() & 0xFFFFFF);
    }

    private static String kindColour(MarkerCategory category) {
        return switch (category) {
            case PLAYER -> "#55B7FF";
            case USER_SHARED -> "#7FD37F";
            case USER_PRIVATE -> "#D9C766";
            case SERVER -> "#B39DDB";
            case LOCAL -> "#FF9E64";
        };
    }

    /** A tint too dark to see on a dark row, lifted until it can be. */
    private static String bright(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (Math.max(r, Math.max(g, b)) < 72) {
            r = Math.max(r, 80);
            g = Math.max(g, 80);
            b = Math.max(b, 80);
        }
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static String span(String colour, String text) {
        return "<font color='" + colour + "'>" + text + "</font>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
