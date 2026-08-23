package meridian.markers;

/**
 * Icons shipped with the game (asset folder {@code UI/WorldMap/MapMarkers});
 * {@code markerImage} is a free string on the wire, so any of these is valid
 * for user/local markers. Rendered as a combo box in the create form.
 */
public enum MarkerIcon {
    UserA("UserA.png"),
    UserB("UserB.png"),
    UserC("UserC.png"),
    UserD("UserD.png"),
    UserE("UserE.png"),
    UserF("UserF.png"),
    Campfire("Campfire.png"),
    Coordinate("Coordinate.png"),
    Death("Death.png"),
    Home("Home.png"),
    Player("Player.png"),
    Portal("Portal.png"),
    PortalInvasion("PortalInvasion.png"),
    Prefab("Prefab.png"),
    Spawn("Spawn.png"),
    TempleGateway("Temple_Gateway.png"),
    Warp("Warp.png");

    private final String file;

    MarkerIcon(String file) {
        this.file = file;
    }

    public String file() {
        return file;
    }
}
