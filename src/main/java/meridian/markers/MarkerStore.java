package meridian.markers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import meridian.protocol.packets.worldmap.MapMarker;
import org.slf4j.Logger;

/**
 * Marker cache: every marker the server ever sent (plus our local ones), keyed
 * per world, together with the user's hide lists. Persisted as one JSON file in
 * the module data dir so the cache — including player last-seen positions —
 * survives reconnects and proxy restarts.
 *
 * <p>Thread model: mutations arrive from Netty event loops (packet handlers)
 * and the Swing EDT (settings callbacks). All maps/sets are concurrent; record
 * upserts go through {@code synchronized} on the store to keep classify+put
 * atomic. Saves are debounced through {@link #saveIfDirty}.
 */
final class MarkerStore {

    /** worldId → markerId → record. */
    private final Map<String, Map<String, MarkerRecord>> worlds = new ConcurrentHashMap<>();
    final Set<String> disabledIds = ConcurrentHashMap.newKeySet();
    final Set<String> disabledIcons = ConcurrentHashMap.newKeySet();
    final Set<String> disabledColors = ConcurrentHashMap.newKeySet();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Logger log;

    MarkerStore(Logger log) {
        this.log = log;
    }

    // ---------------------------------------------------------------- records

    private Map<String, MarkerRecord> world(String worldId) {
        return worlds.computeIfAbsent(worldId, k -> new ConcurrentHashMap<>());
    }

    Collection<MarkerRecord> markers(String worldId) {
        return world(worldId).values();
    }

    MarkerRecord get(String worldId, String id) {
        return world(worldId).get(id);
    }

    /**
     * Merges an incoming wire marker into the cache. Existing records keep
     * their {@code locallyRemoved} flag; player records keep their identity and
     * just refresh position/name/last-seen.
     */
    synchronized MarkerRecord upsertFromServer(String worldId, MapMarker m) {
        MarkerRecord fresh = Markers.toRecord(worldId, m);
        MarkerRecord r = world(worldId).merge(m.id, fresh, (old, inc) -> {
            inc.locallyRemoved = old.locallyRemoved;
            return inc;
        });
        r.live = m.clone();
        dirty.set(true);
        return r;
    }

    synchronized void put(MarkerRecord r) {
        world(r.worldId).put(r.id, r);
        dirty.set(true);
    }

    synchronized MarkerRecord remove(String worldId, String id) {
        MarkerRecord r = world(worldId).remove(id);
        if (r != null) {
            // A record leaving the cache takes its stale hide entry with it.
            disabledIds.remove(id);
            dirty.set(true);
        }
        return r;
    }

    void markDirty() {
        dirty.set(true);
    }

    // ------------------------------------------------------------ hide lists

    /** Toggles one hide-set entry; returns the new "disabled" state. */
    boolean toggle(Set<String> set, String key) {
        boolean disabled = !set.remove(key) && set.add(key);
        dirty.set(true);
        return disabled;
    }

    void resetDisabled() {
        disabledIds.clear();
        disabledIcons.clear();
        disabledColors.clear();
        dirty.set(true);
    }

    // ------------------------------------------------------------ persistence

    private static final class FileModel {
        List<String> disabledIds = List.of();
        List<String> disabledIcons = List.of();
        List<String> disabledColors = List.of();
        List<MarkerRecord> markers = List.of();
    }

    void load(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            FileModel model = gson.fromJson(Files.readString(file), FileModel.class);
            if (model == null) {
                return;
            }
            if (model.disabledIds != null) {
                disabledIds.addAll(model.disabledIds);
            }
            if (model.disabledIcons != null) {
                disabledIcons.addAll(model.disabledIcons);
            }
            if (model.disabledColors != null) {
                disabledColors.addAll(model.disabledColors);
            }
            if (model.markers != null) {
                for (MarkerRecord r : model.markers) {
                    if (r == null || r.id == null || r.worldId == null || r.category == null) {
                        continue;
                    }
                    // A fresh session starts with no live server state.
                    r.online = false;
                    r.live = null;
                    world(r.worldId).put(r.id, r);
                }
            }
            log.info("markers cache loaded: {} marker(s), {} hidden id(s)",
                    worlds.values().stream().mapToInt(Map::size).sum(), disabledIds.size());
        } catch (IOException | RuntimeException e) {
            log.warn("failed to load markers cache from {}: {}", file, e.toString());
        }
    }

    /** Writes the cache if anything changed since the last save. Blocking I/O. */
    void saveIfDirty(Path file) {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        FileModel model = new FileModel();
        model.disabledIds = List.copyOf(disabledIds);
        model.disabledIcons = List.copyOf(disabledIcons);
        model.disabledColors = List.copyOf(disabledColors);
        List<MarkerRecord> all = new ArrayList<>();
        for (Map<String, MarkerRecord> world : worlds.values()) {
            all.addAll(world.values());
        }
        model.markers = all;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(model));
        } catch (IOException e) {
            dirty.set(true);
            log.warn("failed to save markers cache to {}: {}", file, e.toString());
        }
    }
}
