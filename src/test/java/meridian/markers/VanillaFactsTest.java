package meridian.markers;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import meridian.core.api.Chat;
import meridian.core.api.MapMarkers;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.Vec3;
import meridian.core.api.World;

/**
 * What the module takes from core rather than knowing itself: the icon pictures, the name limit
 * and the height a marker is drawn at. A plain test class (no framework on hand offline): every
 * {@code test*} method runs, an {@link AssertionError} fails it.
 */
public class VanillaFactsTest {

    /** Calls the fake core saw, by method name and arguments. */
    private final List<Object[]> calls = new ArrayList<>();

    public void testIconsComeFromCore() {
        MarkerIcons.use(markers(), null);
        check(MarkerIcons.bytes("UserA.png").map(b -> b.length).orElse(-1) == 3, "core's picture");
        check(MarkerIcons.bytes("(none)").isEmpty(), "no icon, no picture");
        check(MarkerIcons.bytes("../UserA.png").isEmpty(), "not a plain name");
    }

    public void testANameTooLongIsNotSent() {
        MarkersView view = new MarkersView(markers(), stub(Chat.class), stub(World.class));
        String name = "x".repeat(MapMarkers.NAME_LIMIT + 1);
        view.create(name, "10", "20", MapMarkers.Icon.Home, 0, false, CreateTarget.SHARED);
        check(calls.stream().noneMatch(c -> c[0].equals("create")), "a name the server refuses was sent");
        check(view.createStatus().contains(String.valueOf(MapMarkers.NAME_LIMIT)), view.createStatus());
    }

    public void testALocalMarkerStandsAtTheServersHeight() {
        MarkersView view = new MarkersView(markers(), stub(Chat.class), stub(World.class));
        view.create("Base", "10", "20", MapMarkers.Icon.Home, 0, false, CreateTarget.LOCAL);
        Object[] made = calls.stream().filter(c -> c[0].equals("createLocal")).findFirst().orElse(null);
        check(made != null, "no local marker made");
        Vec3 at = (Vec3) made[2];
        check(at.y() == MapMarkers.HEIGHT && at.x() == 10 && at.z() == 20, "made at " + at);
        check("Home.png".equals(made[3]), "icon " + made[3]);
    }

    // ------------------------------------------------------------------

    private MapMarkers markers() {
        return (MapMarkers) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MapMarkers.class},
                (proxy, method, args) -> {
                    String n = method.getName();
                    Object[] call = new Object[1 + (args == null ? 0 : args.length)];
                    call[0] = n;
                    if (args != null) System.arraycopy(args, 0, call, 1, args.length);
                    calls.add(call);
                    switch (n) {
                        case "iconImage":
                            return "UserA.png".equals(args[0]) ? Optional.of(new byte[3]) : Optional.empty();
                        case "createLocal":
                            return new Marker("local_1", (String) args[0], (String) args[2], (Vec3) args[1],
                                    (Integer) args[3], MarkerCategory.LOCAL, null, null, null, true, 0L);
                        case "create":
                            return new CompletableFuture<Marker>();
                        case "localOnly":
                            return false;
                        case "all":
                            return List.of();
                        default:
                            return defaultOf(method.getReturnType());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(VanillaFactsTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getReturnType() == Optional.class ? Optional.empty()
                        : defaultOf(method.getReturnType()));
    }

    private static Object defaultOf(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == List.class) return List.of();
        if (t == Optional.class) return Optional.empty();
        return null;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
