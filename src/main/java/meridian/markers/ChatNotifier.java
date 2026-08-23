package meridian.markers;

import meridian.api.session.ProxySession;
import meridian.protocol.FormattedMessage;
import meridian.protocol.packets.interface_.ServerMessage;

/**
 * Sends system chat lines to the client through the Default-channel session.
 *
 * <p>{@code ServerMessage} rides the Default channel, so the session must be
 * captured from a Default-channel packet (SetClientId / JoinWorld /
 * CreateUserMarker / ...) — a WorldMap-channel session would reject the send.
 * Same orange (#ffc800) the server uses for its own marker errors, so refusal
 * notices read as one conversation.
 */
final class ChatNotifier {

    private volatile ProxySession session;

    void bind(ProxySession s) {
        session = s;
    }

    /** The pinned Default-channel session — also the right pipe for C2S sends. */
    ProxySession session() {
        return session;
    }

    void notify(String text) {
        ProxySession s = session;
        if (s == null) {
            return;
        }
        FormattedMessage fm = Markers.raw("[Markers] " + text);
        fm.color = "#ffc800";
        ServerMessage msg = new ServerMessage();
        msg.message = fm;
        s.sendToClient(msg);
    }
}
