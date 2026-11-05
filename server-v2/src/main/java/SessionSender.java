import javax.websocket.Session;

public class SessionSender {
    private final Session session;
    private String pending = null;
    private boolean sending = false;

    public SessionSender(Session session) {
        this.session = session;
    }

    public synchronized void Send(String json) {
        if (sending) {
            pending = json;
            return;
        }
        sending = true;
        DoSend(json);
    }

    private void DoSend(String json) {
        try {
            session.getAsyncRemote().sendText(json, result -> OnSent());
        } catch (Exception e) {
            OnSent();
        }
    }

    // called when the previous send finished
    private void OnSent() {
        String next;
        synchronized (this) {
            next = pending;
            pending = null;
            if (next == null) {
                sending = false;
                return;
            }
        }
        DoSend(next);
    }
}