package meridian.markers;

import java.nio.file.Path;
import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import org.slf4j.Logger;

/**
 * meridian-markers — world-map marker manager for the Meridian proxy.
 *
 * <p>A Jedi (Layer-1) module. What it does:
 * <ul>
 *   <li><b>Cache</b> — every marker the server sends (POIs, user markers,
 *       player positions) is cached per world and persisted, so the list —
 *       including where each player was last seen — survives reconnects.</li>
 *   <li><b>Local-only mode</b> — a checkbox flips marker creation between
 *       "local + server" (forward, fall back to local on refusal) and
 *       "local only" (the server is never asked).</li>
 *   <li><b>Refusal fallback</b> — the server never acks create/remove, so a
 *       forwarded request arms a 5 s timeout; if no confirming
 *       {@code UpdateWorldMap} arrives, the operation is applied locally and a
 *       chat notice is sent.</li>
 *   <li><b>Toggles</b> — every marker, every icon/colour group, and every
 *       category (shared / private / players / server / local) can be shown or
 *       hidden individually; hiding forges {@code removedMarkers}, showing
 *       forges {@code addedMarkers}.</li>
 *   <li><b>Player ghosts</b> — when a player's live marker disappears, an
 *       optional "last seen" ghost marker takes its place.</li>
 * </ul>
 */
public class MarkersModule implements ProxyModule {

    private MarkerStore store;
    private Path dataFile;

    // "Create marker" form state (edited on the EDT, read on button clicks).
    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();
    private volatile String newName = "";
    private volatile String newX = "";
    private volatile String newZ = "";
    private volatile MarkerIcon newIcon = MarkerIcon.UserA;
    private volatile int newTint = 0xFFFF5555;
    private volatile boolean newTintApply;
    private volatile CreateTarget newTarget = CreateTarget.LOCAL;

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        dataFile = ctx.getDataDir().resolve("markers.json");
        store = new MarkerStore(log);
        store.load(dataFile);

        MarkersEngine engine = new MarkersEngine(log, store, ctx.scheduler());

        // S2C WorldMap channel — cache + filter UpdateWorldMap, follow ClearWorldMap.
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new WorldMapChannelHandler(engine));

        // BOTH Default channel — marker requests (C2S), world + chat session (S2C).
        ctx.registerHandler(Direction.BOTH, HandlerPosition.NORMAL,
                (direction, session) -> new DefaultChannelHandler(engine));

        // Debounced cache persistence; markers.json is small, 5 s is plenty.
        ctx.scheduler().scheduleAtFixedRate(() -> store.saveIfDirty(dataFile),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        ctx.onShutdown(() -> store.saveIfDirty(dataFile));

        ctx.registerSettings(SettingsSpec.builder()
                .bool("localOnly",
                        "Local-only markers (never send create/remove to the server)",
                        false, engine::setLocalOnly)
                .bool("rememberPlayers",
                        "Remember last seen player positions (ghost markers)",
                        true, engine::setRememberPlayers)
                .section("Categories", SettingsSpec.builder()
                        .bool("showShared", "Shared (global) markers", true,
                                v -> engine.setCategory(MarkerCategory.USER_SHARED, v))
                        .bool("showPrivate", "Private markers", true,
                                v -> engine.setCategory(MarkerCategory.USER_PRIVATE, v))
                        .bool("showPlayers", "Player markers", true,
                                v -> engine.setCategory(MarkerCategory.PLAYER, v))
                        .bool("showServer", "Server markers (POI, spawn, death, ...)", true,
                                v -> engine.setCategory(MarkerCategory.SERVER, v))
                        .bool("showLocal", "Local markers", true,
                                v -> engine.setCategory(MarkerCategory.LOCAL, v))
                        .build())
                .section("Create marker", SettingsSpec.builder()
                        .string("newName", "Name", "", v -> newName = v)
                        .string("newX", "X", "", v -> newX = v, xBinding)
                        .string("newZ", "Z", "", v -> newZ = v, zBinding)
                        .enum_("newIcon", "Icon", MarkerIcon.class, MarkerIcon.UserA,
                                v -> newIcon = v)
                        .color("newTint", "Tint colour", 0xFFFF5555, v -> newTint = v)
                        .bool("newTintApply", "Apply tint colour", false, v -> newTintApply = v)
                        .enum_("newTarget", "Create as", CreateTarget.class, CreateTarget.LOCAL,
                                v -> newTarget = v)
                        .button("Use current position", () -> {
                            double[] p = engine.currentPos();
                            if (p == null) {
                                engine.setCreateStatus("Position unknown yet — move around first.");
                                return;
                            }
                            xBinding.set(String.valueOf((int) p[0]));
                            zBinding.set(String.valueOf((int) p[2]));
                        })
                        .button("Create marker", () -> engine.createFromUi(
                                newName, newX, newZ, newIcon, newTint, newTintApply, newTarget))
                        .liveText("Result", engine::createStatus)
                        .build())
                .section("Markers (click a row to toggle & select)", SettingsSpec.builder()
                        .string("search", "Search (name / icon / colour / category)", "",
                                engine::setSearch)
                        .liveList("Markers", engine::markerRowsView, engine::onMarkerRowClick)
                        .liveText("Selected", engine::selectedLine)
                        .button("Delete selected marker", engine::deleteSelected)
                        .build())
                .section("Groups (click a row to toggle)", SettingsSpec.builder()
                        .liveList("Icon / colour groups", engine::groupRowsView, engine::onGroupRowClick)
                        .build())
                .liveText("Status", engine::statusLine)
                .button("Re-enable all hidden markers", engine::resetDisabled)
                .button("Forget last-seen players (this world)", engine::forgetLastSeen)
                .button("Delete all local markers (this world)", engine::deleteAllLocal)
                .persistent("localOnly", "rememberPlayers",
                        "showShared", "showPrivate", "showPlayers", "showServer", "showLocal")
                .build());

        log.info("meridian-markers enabled — cache file {}", dataFile);
    }

    @Override
    public void onDisable() {
        if (store != null && dataFile != null) {
            store.saveIfDirty(dataFile);
        }
    }
}
