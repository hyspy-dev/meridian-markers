package meridian.markers;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Chat;
import meridian.core.api.MapMarkers;
import meridian.core.api.MarkerArchive;
import meridian.core.api.MarkerCategory;
import meridian.core.api.MarkerSource;
import meridian.core.api.Vec3;
import meridian.core.api.World;

/**
 * meridian-markers - a page for managing what is on the world map.
 *
 * <p>The map itself belongs to {@link MapMarkers} in core: what markers exist, which of them the
 * client is shown, and what to do when the server quietly refuses a request. This module is the
 * face of it - a searchable list, groups by icon and colour, and a form for placing a marker -
 * and touches no packets, so one build serves every version of the game.
 *
 * <p>It is also where the markers are kept. Core remembers them for as long as it runs; the file
 * that outlives a session is {@link MarkerKeeper}'s, and so is the {@link MarkerSource} that hands
 * markers and their pictures to anything drawing a map.
 */
public class MarkersModule implements ProxyModule {

    /** Often enough to feel immediate, rarely enough that a busy world is not re-read constantly. */
    private static final Duration REFRESH = Duration.ofMillis(500);

    // The create form, filled in on the settings thread and read when a button is pressed.
    private final SettingBinding<String> xField = new SettingBinding<>();
    private final SettingBinding<String> zField = new SettingBinding<>();
    private volatile String name = "";
    private volatile String x = "";
    private volatile String z = "";
    private volatile MarkerIcon icon = MarkerIcon.UserA;
    private volatile int tint = 0xFFFF5555;
    private volatile boolean applyTint;
    private volatile CreateTarget target = CreateTarget.LOCAL;

    @Override
    public void onEnable(ModuleContext ctx) {
        MapMarkers markers = ctx.services().require(MapMarkers.class);
        // The markers are kept here, not in core: core remembers them while it runs, and this
        // writes them down, hands them back at the next start, and offers them to anything
        // drawing a map of its own.
        ctx.services().provide(MarkerSource.class,
                MarkerKeeper.start(ctx, ctx.services().require(MarkerArchive.class)));
        MarkersView view = new MarkersView(markers, ctx.services().require(Chat.class),
                ctx.services().require(World.class));

        // Markers arrive in bursts as the player walks, and every one of them could change what
        // the list says. Redrawing on a timer collapses a burst into one pass.
        ctx.scheduler().scheduleAtFixedRate(view::refresh, REFRESH, REFRESH);

        ctx.registerSettings(SettingsSpec.builder()
                .bool("localOnly",
                        "Keep markers to myself (never ask the server)",
                        false, markers::setLocalOnly)
                .bool("rememberPlayers",
                        "Leave a marker where a player was last seen",
                        true, markers::setPlayerGhosts)
                .section("Show", SettingsSpec.builder()
                        .bool("showShared", "Markers shared with everyone", true,
                                v -> view.setKindShown(MarkerCategory.USER_SHARED, v))
                        .bool("showPrivate", "Markers players keep to themselves", true,
                                v -> view.setKindShown(MarkerCategory.USER_PRIVATE, v))
                        .bool("showPlayers", "Where players are", true,
                                v -> view.setKindShown(MarkerCategory.PLAYER, v))
                        .bool("showServer", "The world's own (spawn, homes, points of interest)",
                                true, v -> view.setKindShown(MarkerCategory.SERVER, v))
                        .bool("showLocal", "Mine", true,
                                v -> view.setKindShown(MarkerCategory.LOCAL, v))
                        .build())
                .section("Place a marker", SettingsSpec.builder()
                        .string("newName", "Name", "", v -> name = v)
                        .string("newX", "X", "", v -> x = v, xField)
                        .string("newZ", "Z", "", v -> z = v, zField)
                        .enum_("newIcon", "Icon", MarkerIcon.class, MarkerIcon.UserA, v -> icon = v)
                        .color("newTint", "Colour", 0xFFFF5555, v -> tint = v)
                        .bool("newTintApply", "Use that colour", false, v -> applyTint = v)
                        .enum_("newTarget", "Who can see it", CreateTarget.class,
                                CreateTarget.LOCAL, v -> target = v)
                        .button("Where I am now", () -> {
                            Vec3 here = view.here();
                            if (here == null) {
                                view.setCreateStatus("I do not know where you are yet - move.");
                                return;
                            }
                            xField.set(String.valueOf((int) here.x()));
                            zField.set(String.valueOf((int) here.z()));
                        })
                        .button("Place it", () ->
                                view.create(name, x, z, icon, tint, applyTint, target))
                        .liveText("Result", view::createStatus)
                        .build())
                .section("Markers (click a row to show or hide it)", SettingsSpec.builder()
                        .string("search", "Search by name, icon, colour or kind", "",
                                view::setSearch)
                        .liveList("Markers", view::markerRows, view::onMarkerClick)
                        .liveText("Selected", view::selectedLine)
                        .button("Delete the selected marker", view::deleteSelected)
                        .build())
                .section("Groups (click a row to show or hide the whole group)",
                        SettingsSpec.builder()
                                .liveList("By icon and colour", view::groupRows, view::onGroupClick)
                                .build())
                .liveText("Status", view::status)
                .button("Show everything again", view::showEverything)
                .button("Forget where players were last seen", view::forgetOfflinePlayers)
                .button("Delete all of my markers in this world", view::deleteLocal)
                .persistent("localOnly", "rememberPlayers",
                        "showShared", "showPrivate", "showPlayers", "showServer", "showLocal")
                .build());

        ctx.getLogger().info("meridian-markers enabled");
    }
}
