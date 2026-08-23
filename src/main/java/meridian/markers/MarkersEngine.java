package meridian.markers;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.api.packet.PacketHandler.Action;
import meridian.api.session.ProxySession;
import meridian.protocol.Color;
import meridian.protocol.packets.player.ClientMovement;
import meridian.protocol.packets.player.RemoveMapMarker;
import meridian.protocol.packets.worldmap.CreateUserMarker;
import meridian.protocol.packets.worldmap.MapMarker;
import meridian.protocol.packets.worldmap.TeleportToWorldMapMarker;
import meridian.protocol.packets.worldmap.UpdateWorldMap;
import org.slf4j.Logger;

/**
 * Shared coordinator behind both packet handlers and the settings UI.
 *
 * <p>Core mechanics (verified against the Hytale server source):
 * <ul>
 *   <li>The server never acks marker create/remove. Success shows up as the
 *       marker (id {@code user_shared_/user_personal_...}) appearing in
 *       {@code UpdateWorldMap.addedMarkers} within ~1 map-tracker tick; refusal
 *       is only an orange chat message. So each forwarded request arms a
 *       timeout — no confirmation in {@link #CONFIRM_TIMEOUT} ⇒ refused ⇒ we
 *       apply the operation locally and notify in chat.</li>
 *   <li>Hiding a marker = forged {@code removedMarkers}; showing = forged
 *       {@code addedMarkers}. The server's own diff tracker is unaffected: it
 *       keeps emitting updates, which we strip while the marker is hidden.</li>
 *   <li>Player markers ({@code Player-<uuid>}) are removed the moment the
 *       player leaves the world — that removal is our "last seen" snapshot,
 *       optionally replaced by a forged ghost marker.</li>
 * </ul>
 */
final class MarkersEngine {

    /** Server marker tick is sub-second; 5 s comfortably covers refusal detection. */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    /** The server echoes the exact floats we sent, so a tight box is enough. */
    private static final double CONFIRM_EPSILON = 0.5;

    private static final DateTimeFormatter SEEN_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final Logger log;
    private final MarkerStore store;
    private final Scheduler scheduler;
    final ChatNotifier chat = new ChatNotifier();

    /** WorldMap-channel session — the only valid pipe for forged UpdateWorldMap. */
    private volatile ProxySession mapSession;
    private volatile String worldId = "";
    /** Marker ids we believe the client currently displays (incl. forged ones). */
    private final Set<String> clientVisible = ConcurrentHashMap.newKeySet();
    /** Set after JoinWorld/ClearWorldMap: re-inject local+ghost markers on the next map update. */
    private volatile boolean mapDirty;

    /** Learned from the first confirmed create (PlacedBy carries our identity). */
    private volatile UUID selfId;
    private volatile String selfName;

    // Settings state (written from the EDT, read from Netty threads).
    private volatile boolean localOnly;
    private volatile boolean rememberPlayers = true;
    private volatile boolean showPlayers = true;
    private volatile boolean showShared = true;
    private volatile boolean showPrivate = true;
    private volatile boolean showServer = true;
    private volatile boolean showLocal = true;

    private static final class PendingCreate {
        final float x;
        final float z;
        final boolean shared;
        final String name;
        final String image;
        final int tintRgb;
        volatile ScheduledFuture<?> timer;

        PendingCreate(CreateUserMarker req) {
            this.x = req.x;
            this.z = req.z;
            this.shared = req.shared;
            this.name = req.name;
            this.image = req.markerImage;
            this.tintRgb = Markers.rgb(req.tintColor);
        }
    }

    private static final class PendingRemove {
        final String id;
        volatile ScheduledFuture<?> timer;

        PendingRemove(String id) {
            this.id = id;
        }
    }

    private final ConcurrentLinkedQueue<PendingCreate> pendingCreates = new ConcurrentLinkedQueue<>();
    private final Map<String, PendingRemove> pendingRemoves = new ConcurrentHashMap<>();

    // UI snapshots — rebuilt on every mutation, polled from the EDT.
    record Rows(List<String> rows, List<String> keys) {
        static final Rows EMPTY = new Rows(List.of(), List.of());
    }

    private volatile Rows markerRows = Rows.EMPTY;
    private volatile Rows groupRows = Rows.EMPTY;
    private volatile String status = "No data yet.";
    /** List filter, lower-cased; empty = show everything. */
    private volatile String search = "";
    /** Marker id of the last clicked row — target of "Delete selected". */
    private volatile String selectedId;
    /** Outcome line of the create form. */
    private volatile String createStatus = "";

    // Player position mirrored from C2S ClientMovement. The client sends an
    // absolute fix periodically; between fixes it sends short deltas scaled by
    // 10000 (see the server's RELATIVE_POSITION_DELTA_SCALE).
    private static final double RELATIVE_POSITION_DELTA_SCALE = 10000.0;
    private volatile double posX;
    private volatile double posY = 100;
    private volatile double posZ;
    private volatile boolean posKnown;

    MarkersEngine(Logger log, MarkerStore store, Scheduler scheduler) {
        this.log = log;
        this.store = store;
        this.scheduler = scheduler;
    }

    // ================================================================ packets

    Action onWorldMapUpdate(UpdateWorldMap m, ProxySession session) {
        mapSession = session;
        String w = worldId;
        long now = System.currentTimeMillis();
        boolean mutated = false;
        List<MapMarker> outAdds = new ArrayList<>();
        List<String> outRemoves = new ArrayList<>();
        if (m.removedMarkers != null) {
            Collections.addAll(outRemoves, m.removedMarkers);
        }

        if (m.addedMarkers != null) {
            for (MapMarker mk : m.addedMarkers) {
                if (mk == null || mk.id == null) {
                    continue;
                }
                MarkerRecord r = store.upsertFromServer(w, mk);
                confirmCreate(r);
                if (r.category == MarkerCategory.PLAYER && r.playerId != null) {
                    // Player is live again — retire their ghost, if shown.
                    String ghost = Markers.ghostId(r.playerId);
                    if (clientVisible.remove(ghost)) {
                        outRemoves.add(ghost);
                        mutated = true;
                    }
                }
                if (r.locallyRemoved || !isVisible(r)) {
                    clientVisible.remove(mk.id);
                    mutated = true; // strip from the forwarded frame
                    continue;
                }
                outAdds.add(mk);
                clientVisible.add(mk.id);
            }
        }

        if (m.removedMarkers != null) {
            for (String id : m.removedMarkers) {
                if (id == null) {
                    continue;
                }
                boolean confirmedRemove = confirmRemove(id);
                clientVisible.remove(id);
                MarkerRecord r = store.get(w, id);
                if (r == null) {
                    continue;
                }
                if (confirmedRemove && r.category != MarkerCategory.PLAYER) {
                    // The user asked, the server obliged — genuinely gone.
                    store.remove(w, id);
                    continue;
                }
                // View-distance cull, someone else's delete, or a player
                // leaving — keep the cache entry as "last seen".
                r.online = false;
                r.lastSeenMs = now;
                store.markDirty();
                if (r.category == MarkerCategory.PLAYER && r.playerId != null
                        && rememberPlayers && isVisible(r)) {
                    outAdds.add(Markers.buildGhost(r));
                    clientVisible.add(Markers.ghostId(r.playerId));
                    mutated = true;
                }
            }
        }

        if (mapDirty) {
            mapDirty = false;
            for (MarkerRecord r : store.markers(w)) {
                mutated |= reinject(r, outAdds);
            }
        }

        rebuildRows();
        if (!mutated) {
            return Action.FORWARD;
        }
        m.addedMarkers = outAdds.isEmpty() ? null : outAdds.toArray(MapMarker[]::new);
        m.removedMarkers = outRemoves.isEmpty() ? null : outRemoves.toArray(String[]::new);
        return Action.MODIFIED;
    }

    /** Local + ghost markers are client-side only — restore them after a map reset. */
    private boolean reinject(MarkerRecord r, List<MapMarker> outAdds) {
        if (r.category == MarkerCategory.LOCAL) {
            if (isVisible(r) && !clientVisible.contains(r.id)) {
                outAdds.add(Markers.buildLocal(r));
                clientVisible.add(r.id);
                return true;
            }
        } else if (r.category == MarkerCategory.PLAYER && r.playerId != null && !r.online) {
            String ghost = Markers.ghostId(r.playerId);
            if (rememberPlayers && isVisible(r) && !clientVisible.contains(ghost)) {
                outAdds.add(Markers.buildGhost(r));
                clientVisible.add(ghost);
                return true;
            }
        }
        return false;
    }

    void onClearWorldMap() {
        clientVisible.clear();
        mapDirty = true;
        long now = System.currentTimeMillis();
        for (MarkerRecord r : store.markers(worldId)) {
            if (r.online) {
                r.online = false;
                r.lastSeenMs = now;
            }
        }
        store.markDirty();
        rebuildRows();
    }

    void onJoinWorld(UUID world, ProxySession session) {
        chat.bind(session);
        String w = world.toString();
        if (w.equals(worldId)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (MarkerRecord r : store.markers(worldId)) {
            if (r.online) {
                r.online = false;
                r.lastSeenMs = now;
            }
        }
        store.markDirty();
        worldId = w;
        clientVisible.clear();
        mapDirty = true;
        rebuildRows();
        log.debug("world changed to {}", w);
    }

    void onClientMovement(ClientMovement m) {
        if (m.absolutePosition != null) {
            posX = m.absolutePosition.x;
            posY = m.absolutePosition.y;
            posZ = m.absolutePosition.z;
            posKnown = true;
        } else if (m.relativePosition != null && posKnown) {
            posX += m.relativePosition.x / RELATIVE_POSITION_DELTA_SCALE;
            posY += m.relativePosition.y / RELATIVE_POSITION_DELTA_SCALE;
            posZ += m.relativePosition.z / RELATIVE_POSITION_DELTA_SCALE;
        }
    }

    /** Last known player position, or {@code null} before the first movement. */
    double[] currentPos() {
        return posKnown ? new double[]{posX, posY, posZ} : null;
    }

    Action onCreateUserMarker(CreateUserMarker req, ProxySession session) {
        chat.bind(session);
        if (localOnly) {
            MarkerRecord r = createLocal(new PendingCreate(req));
            chat.notify("Marker '" + displayName(r) + "' created locally (local-only mode).");
            return Action.DROP;
        }
        PendingCreate pc = new PendingCreate(req);
        pendingCreates.add(pc);
        pc.timer = scheduler.schedule(() -> onCreateTimeout(pc), CONFIRM_TIMEOUT);
        return Action.FORWARD;
    }

    private void onCreateTimeout(PendingCreate pc) {
        if (!pendingCreates.remove(pc)) {
            return; // confirmed in the meantime
        }
        MarkerRecord r = createLocal(pc);
        chat.notify("Server rejected marker '" + displayName(r)
                + "' — created locally instead.");
        createStatus = "Server rejected '" + displayName(r) + "' — created locally.";
        log.info("create not confirmed in {}s — local fallback for '{}' at ({}, {})",
                CONFIRM_TIMEOUT.toSeconds(), r.name, (int) r.x, (int) r.z);
    }

    private MarkerRecord createLocal(PendingCreate pc) {
        MarkerRecord r = new MarkerRecord();
        r.id = Markers.newLocalId();
        r.worldId = worldId;
        r.name = pc.name == null ? "" : pc.name;
        r.markerImage = pc.image == null ? Markers.DEFAULT_USER_IMAGE : pc.image;
        r.x = pc.x;
        r.y = 100; // the server pins user markers at y=100 as well
        r.z = pc.z;
        r.tintRgb = pc.tintRgb;
        r.category = MarkerCategory.LOCAL;
        r.placedById = selfId;
        r.placedByName = selfName;
        r.lastSeenMs = System.currentTimeMillis();
        r.online = true;
        store.put(r);
        if (isVisible(r)) {
            ProxySession s = mapSession;
            if (s != null) {
                s.sendToClient(new UpdateWorldMap(null,
                        new MapMarker[]{Markers.buildLocal(r)}, null));
                clientVisible.add(r.id);
            } else {
                mapDirty = true;
            }
        }
        rebuildRows();
        return r;
    }

    private static String displayName(MarkerRecord r) {
        return r.name == null || r.name.isEmpty() ? r.id : r.name;
    }

    private void confirmCreate(MarkerRecord r) {
        if (r.category != MarkerCategory.USER_SHARED && r.category != MarkerCategory.USER_PRIVATE) {
            return;
        }
        boolean shared = r.category == MarkerCategory.USER_SHARED;
        for (Iterator<PendingCreate> it = pendingCreates.iterator(); it.hasNext(); ) {
            PendingCreate pc = it.next();
            if (pc.shared == shared
                    && Math.abs(pc.x - r.x) < CONFIRM_EPSILON
                    && Math.abs(pc.z - r.z) < CONFIRM_EPSILON
                    && pendingCreates.remove(pc)) {
                ScheduledFuture<?> t = pc.timer;
                if (t != null) {
                    t.cancel(false);
                }
                if (r.placedById != null) {
                    selfId = r.placedById;
                    selfName = r.placedByName;
                }
                createStatus = "Server confirmed marker '" + displayName(r) + "'.";
                return;
            }
        }
    }

    String createStatus() {
        return createStatus;
    }

    void setCreateStatus(String s) {
        createStatus = s;
    }

    /**
     * "Create marker" form handler. LOCAL (or local-only mode) forges the
     * marker straight to the client; PRIVATE/SHARED send a real
     * {@code CreateUserMarker} and reuse the refusal-timeout fallback. Note the
     * server only accepts markers near the player (~2× map view radius).
     */
    void createFromUi(String name, String xText, String zText, MarkerIcon icon,
                      int tintArgb, boolean applyTint, CreateTarget target) {
        if (worldId.isEmpty()) {
            createStatus = "No world yet — join a world first.";
            return;
        }
        double x;
        double z;
        try {
            x = Double.parseDouble(xText.trim());
            z = Double.parseDouble(zText.trim());
        } catch (RuntimeException e) {
            createStatus = "X and Z must be numbers.";
            return;
        }
        String nm = name == null ? "" : name.trim();
        Color tint = applyTint ? Markers.color(tintArgb & 0xFFFFFF) : null;
        boolean shared = target == CreateTarget.SHARED;
        CreateUserMarker pkt = new CreateUserMarker((float) x, (float) z,
                nm.isEmpty() ? null : nm, icon.file(), tint, shared);

        if (target == CreateTarget.LOCAL || localOnly) {
            MarkerRecord r = createLocal(new PendingCreate(pkt));
            createStatus = "Local marker '" + displayName(r) + "' created at ("
                    + (int) x + ", " + (int) z + ")."
                    + (target != CreateTarget.LOCAL ? " (local-only mode)" : "");
            return;
        }
        if (nm.length() > 24) {
            createStatus = "Name too long for the server (max 24 chars).";
            return;
        }
        ProxySession s = chat.session();
        if (s == null) {
            createStatus = "Not connected yet — use target LOCAL.";
            return;
        }
        PendingCreate pc = new PendingCreate(pkt);
        pendingCreates.add(pc);
        pc.timer = scheduler.schedule(() -> onCreateTimeout(pc), CONFIRM_TIMEOUT);
        // Bypasses the handler chain, so our own C2S intercept won't loop.
        s.sendToServer(pkt);
        createStatus = "Requested " + (shared ? "shared" : "private") + " marker at ("
                + (int) x + ", " + (int) z + ") — if refused, it becomes local.";
    }

    Action onRemoveMapMarker(RemoveMapMarker req, ProxySession session) {
        chat.bind(session);
        String id = req.markerId;
        if (id == null || id.isEmpty()) {
            return Action.FORWARD;
        }
        String w = worldId;
        if (Markers.isProxyId(id)) {
            // Ours — the server has never heard of this id.
            if (Markers.isGhostId(id)) {
                forgetGhost(w, id);
                chat.notify("Last-seen marker removed.");
            } else {
                store.remove(w, id);
                chat.notify("Local marker removed.");
            }
            forgeRemove(id);
            rebuildRows();
            return Action.DROP;
        }
        if (localOnly) {
            hideLocally(w, id);
            chat.notify("Marker hidden locally (local-only mode).");
            rebuildRows();
            return Action.DROP;
        }
        armPendingRemove(id);
        return Action.FORWARD;
    }

    private void armPendingRemove(String id) {
        PendingRemove pr = new PendingRemove(id);
        pendingRemoves.put(id, pr);
        pr.timer = scheduler.schedule(() -> onRemoveTimeout(pr), CONFIRM_TIMEOUT);
    }

    private void onRemoveTimeout(PendingRemove pr) {
        if (!pendingRemoves.remove(pr.id, pr)) {
            return; // confirmed in the meantime
        }
        hideLocally(worldId, pr.id);
        chat.notify("Server rejected marker removal — hidden locally instead.");
        log.info("remove of '{}' not confirmed in {}s — hidden locally",
                pr.id, CONFIRM_TIMEOUT.toSeconds());
        rebuildRows();
    }

    private boolean confirmRemove(String id) {
        PendingRemove pr = pendingRemoves.remove(id);
        if (pr == null) {
            return false;
        }
        ScheduledFuture<?> t = pr.timer;
        if (t != null) {
            t.cancel(false);
        }
        return true;
    }

    private void hideLocally(String w, String id) {
        MarkerRecord r = store.get(w, id);
        if (r != null) {
            r.locallyRemoved = true;
            store.markDirty();
        }
        forgeRemove(id);
    }

    private void forgetGhost(String w, String ghostId) {
        for (MarkerRecord r : store.markers(w)) {
            if (r.playerId != null && Markers.ghostId(r.playerId).equals(ghostId)) {
                store.remove(w, r.id);
                return;
            }
        }
    }

    Action onTeleportToMarker(TeleportToWorldMapMarker req, ProxySession session) {
        chat.bind(session);
        if (Markers.isProxyId(req.id)) {
            chat.notify("That marker exists only locally — the server cannot teleport to it.");
            return Action.DROP;
        }
        return Action.FORWARD;
    }

    // ============================================================ visibility

    private boolean isVisible(MarkerRecord r) {
        boolean category = switch (r.category) {
            case PLAYER -> showPlayers;
            case USER_SHARED -> showShared;
            case USER_PRIVATE -> showPrivate;
            case SERVER -> showServer;
            case LOCAL -> showLocal;
        };
        return category
                && !store.disabledIds.contains(r.id)
                && !store.disabledIcons.contains(r.iconKey())
                && !store.disabledColors.contains(r.colorKey());
    }

    /** Recomputes the whole current world against the client and forges the diff. */
    void applyVisibility() {
        String w = worldId;
        List<MapMarker> adds = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        for (MarkerRecord r : store.markers(w)) {
            if (r.category == MarkerCategory.PLAYER && r.playerId != null) {
                diffOne(r.id, r.online && !r.locallyRemoved && isVisible(r),
                        () -> r.live, adds, removes);
                diffOne(Markers.ghostId(r.playerId),
                        !r.online && rememberPlayers && isVisible(r),
                        () -> Markers.buildGhost(r), adds, removes);
            } else if (r.category == MarkerCategory.LOCAL) {
                diffOne(r.id, isVisible(r), () -> Markers.buildLocal(r), adds, removes);
            } else {
                // Server-owned: can only re-show what the server currently emits.
                diffOne(r.id, r.online && !r.locallyRemoved && isVisible(r),
                        () -> r.live, adds, removes);
            }
        }
        if (!adds.isEmpty() || !removes.isEmpty()) {
            ProxySession s = mapSession;
            if (s != null) {
                s.sendToClient(new UpdateWorldMap(null,
                        adds.isEmpty() ? null : adds.toArray(MapMarker[]::new),
                        removes.isEmpty() ? null : removes.toArray(String[]::new)));
            }
        }
        rebuildRows();
    }

    private void diffOne(String id, boolean should, Supplier<MapMarker> marker,
                         List<MapMarker> adds, List<String> removes) {
        boolean shown = clientVisible.contains(id);
        if (should && !shown) {
            MapMarker mk = marker.get();
            if (mk != null) {
                adds.add(mk);
                clientVisible.add(id);
            }
        } else if (!should && shown) {
            removes.add(id);
            clientVisible.remove(id);
        }
    }

    private void forgeRemove(String id) {
        clientVisible.remove(id);
        ProxySession s = mapSession;
        if (s != null) {
            s.sendToClient(new UpdateWorldMap(null, null, new String[]{id}));
        }
    }

    // ============================================================== settings

    void setLocalOnly(boolean v) {
        localOnly = v;
    }

    void setRememberPlayers(boolean v) {
        rememberPlayers = v;
        applyVisibility();
    }

    void setCategory(MarkerCategory category, boolean v) {
        switch (category) {
            case PLAYER -> showPlayers = v;
            case USER_SHARED -> showShared = v;
            case USER_PRIVATE -> showPrivate = v;
            case SERVER -> showServer = v;
            case LOCAL -> showLocal = v;
        }
        applyVisibility();
    }

    void resetDisabled() {
        store.resetDisabled();
        for (MarkerRecord r : store.markers(worldId)) {
            r.locallyRemoved = false;
        }
        store.markDirty();
        applyVisibility();
    }

    void forgetLastSeen() {
        String w = worldId;
        for (MarkerRecord r : new ArrayList<>(store.markers(w))) {
            if (r.category == MarkerCategory.PLAYER && !r.online) {
                store.remove(w, r.id);
                if (r.playerId != null) {
                    forgeRemove(Markers.ghostId(r.playerId));
                }
            }
        }
        rebuildRows();
    }

    void deleteAllLocal() {
        String w = worldId;
        for (MarkerRecord r : new ArrayList<>(store.markers(w))) {
            if (r.category == MarkerCategory.LOCAL) {
                store.remove(w, r.id);
                forgeRemove(r.id);
            }
        }
        rebuildRows();
    }

    // ==================================================================== UI

    List<String> markerRowsView() {
        return markerRows.rows();
    }

    List<String> groupRowsView() {
        return groupRows.rows();
    }

    String statusLine() {
        return status;
    }

    void setSearch(String filter) {
        search = filter == null ? "" : filter.trim().toLowerCase();
        rebuildRows();
    }

    /** One-line description of the last clicked row, for the "Selected" label. */
    String selectedLine() {
        String id = selectedId;
        if (id == null) {
            return "—";
        }
        MarkerRecord r = store.get(worldId, id);
        if (r == null) {
            return "— (marker is gone)";
        }
        return displayName(r) + "  (" + (int) r.x + ", " + (int) r.z + ")  "
                + r.category.label();
    }

    /**
     * Deletes the last clicked marker. Local/ghost markers die immediately;
     * server-owned ones go through a real {@code RemoveMapMarker} (with the
     * usual refusal timeout), or a local hide in local-only mode.
     */
    void deleteSelected() {
        String id = selectedId;
        if (id == null) {
            return;
        }
        selectedId = null;
        String w = worldId;
        MarkerRecord r = store.get(w, id);
        if (r == null) {
            return;
        }
        switch (r.category) {
            case LOCAL -> {
                store.remove(w, id);
                forgeRemove(id);
                chat.notify("Local marker '" + displayName(r) + "' deleted.");
            }
            case PLAYER -> {
                if (r.online) {
                    chat.notify("'" + displayName(r)
                            + "' is a live player marker — hide it instead of deleting.");
                    return;
                }
                store.remove(w, id);
                if (r.playerId != null) {
                    forgeRemove(Markers.ghostId(r.playerId));
                }
                chat.notify("Forgot last-seen position of '" + displayName(r) + "'.");
            }
            default -> {
                if (localOnly) {
                    hideLocally(w, id);
                    chat.notify("Marker '" + displayName(r)
                            + "' hidden locally (local-only mode).");
                    break;
                }
                ProxySession s = chat.session();
                if (s == null) {
                    hideLocally(w, id);
                    chat.notify("No server session — marker '" + displayName(r)
                            + "' hidden locally.");
                    break;
                }
                // Bypasses the handler chain, so our own C2S intercept won't loop.
                armPendingRemove(id);
                s.sendToServer(new RemoveMapMarker(id));
            }
        }
        applyVisibility();
    }

    void onMarkerRowClick(int idx) {
        Rows rows = markerRows;
        if (idx < 0 || idx >= rows.keys().size()) {
            return;
        }
        String id = rows.keys().get(idx);
        selectedId = id;
        MarkerRecord r = store.get(worldId, id);
        if (r != null && r.locallyRemoved) {
            // Un-delete beats un-hide: a click restores a locally removed marker.
            r.locallyRemoved = false;
            store.markDirty();
        } else {
            store.toggle(store.disabledIds, id);
        }
        applyVisibility();
    }

    void onGroupRowClick(int idx) {
        Rows rows = groupRows;
        if (idx < 0 || idx >= rows.keys().size()) {
            return;
        }
        String key = rows.keys().get(idx);
        if (key.startsWith("icon:")) {
            store.toggle(store.disabledIcons, key.substring("icon:".length()));
        } else if (key.startsWith("color:")) {
            store.toggle(store.disabledColors, key.substring("color:".length()));
        }
        applyVisibility();
    }

    // Rows are HTML — the proxy renders liveList rows through JLabel-based
    // cells on a dark background, so <font> tags give us colour for free.
    private static final String DIM = "#8A8A8A";
    private static final String ON_MARK = "<font color='#7FD37F'>[x]</font> ";
    private static final String OFF_MARK = "<font color='#666666'>[&nbsp;]</font> ";

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String catColor(MarkerCategory c) {
        return switch (c) {
            case PLAYER -> "#55B7FF";
            case USER_SHARED -> "#7FD37F";
            case USER_PRIVATE -> "#D9C766";
            case SERVER -> "#B39DDB";
            case LOCAL -> "#FF9E64";
        };
    }

    /** Tint hex for display — very dark tints get floored so they stay visible. */
    private static String displayHex(int rgb) {
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

    private static String span(String color, String text) {
        return "<font color='" + color + "'>" + text + "</font>";
    }

    private boolean matchesSearch(MarkerRecord r) {
        String f = search;
        if (f.isEmpty()) {
            return true;
        }
        String haystack = (displayName(r) + ' ' + r.id + ' ' + r.iconKey() + ' '
                + r.colorKey() + ' ' + r.category.label()
                + (r.category == MarkerCategory.PLAYER ? (r.online ? " online" : " offline") : ""))
                .toLowerCase();
        return haystack.contains(f);
    }

    private void rebuildRows() {
        String w = worldId;
        List<MarkerRecord> list = new ArrayList<>(store.markers(w));
        list.sort(Comparator
                .comparingInt((MarkerRecord r) -> r.category.ordinal())
                .thenComparing(r -> r.name == null ? "" : r.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.id));

        List<String> rows = new ArrayList<>(list.size());
        List<String> keys = new ArrayList<>(list.size());
        Map<String, Integer> icons = new TreeMap<>();
        Map<String, Integer> colors = new TreeMap<>();
        int visible = 0;
        int playersOnline = 0;
        int playersRemembered = 0;

        for (MarkerRecord r : list) {
            icons.merge(r.iconKey(), 1, Integer::sum);
            colors.merge(r.colorKey(), 1, Integer::sum);
            boolean on = !r.locallyRemoved && isVisible(r);
            if (on) {
                visible++;
            }
            if (r.category == MarkerCategory.PLAYER) {
                if (r.online) {
                    playersOnline++;
                } else {
                    playersRemembered++;
                }
            }
            if (!matchesSearch(r)) {
                continue;
            }
            StringBuilder sb = new StringBuilder("<html>");
            sb.append(on ? ON_MARK : OFF_MARK);
            String img = MarkerIcons.imgTag(r.iconKey(), 14);
            sb.append(img);
            if (r.tintRgb >= 0) {
                sb.append(span(displayHex(r.tintRgb), "■")).append(' ');
            }
            String name = esc(displayName(r));
            sb.append(on ? name : span("#777777", name));
            sb.append(' ').append(span(DIM, "(" + (int) r.x + ", " + (int) r.z + ")"));
            sb.append(' ').append(span(catColor(r.category), r.category.label()));
            if (img.isEmpty() && !"(none)".equals(r.iconKey())) {
                sb.append(' ').append(span(DIM, esc(r.iconKey())));
            }
            if (r.category == MarkerCategory.PLAYER) {
                sb.append(' ').append(r.online
                        ? span("#7FD37F", "online")
                        : span(DIM, "seen " + SEEN_FMT.format(Instant.ofEpochMilli(r.lastSeenMs)
                                .atZone(ZoneId.systemDefault()))));
            } else if (!r.online && r.category != MarkerCategory.LOCAL) {
                sb.append(' ').append(span(DIM, "[cached]"));
            }
            if (r.locallyRemoved) {
                sb.append(' ').append(span("#E06C75", "[deleted locally — click to restore]"));
            }
            rows.add(sb.toString());
            keys.add(r.id);
        }
        markerRows = new Rows(List.copyOf(rows), List.copyOf(keys));

        List<String> groupRowList = new ArrayList<>();
        List<String> groupKeyList = new ArrayList<>();
        for (Map.Entry<String, Integer> e : icons.entrySet()) {
            boolean off = store.disabledIcons.contains(e.getKey());
            groupRowList.add("<html>" + (off ? OFF_MARK : ON_MARK) + "icon: "
                    + MarkerIcons.imgTag(e.getKey(), 14) + esc(e.getKey())
                    + ' ' + span(DIM, "(" + e.getValue() + ")"));
            groupKeyList.add("icon:" + e.getKey());
        }
        for (Map.Entry<String, Integer> e : colors.entrySet()) {
            boolean off = store.disabledColors.contains(e.getKey());
            String key = e.getKey();
            String swatch = "(none)".equals(key)
                    ? span(DIM, key)
                    : span(displayHex(Integer.parseInt(key.substring(1), 16)), "■ " + key);
            groupRowList.add("<html>" + (off ? OFF_MARK : ON_MARK) + "color: "
                    + swatch + ' ' + span(DIM, "(" + e.getValue() + ")"));
            groupKeyList.add("color:" + key);
        }
        groupRows = new Rows(List.copyOf(groupRowList), List.copyOf(groupKeyList));

        String filterNote = search.isEmpty() ? "" : ", filter: " + keys.size() + " match(es)";
        status = "World " + (w.isEmpty() ? "?" : w.substring(0, Math.min(8, w.length())))
                + " — " + list.size() + " cached, " + visible + " visible, players "
                + playersOnline + " online / " + playersRemembered + " remembered" + filterNote;
    }
}
