import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

    // logger
    private static final Logger logger = Logger.getLogger(LeaderboardEndpoint.class.getName());
    // resortID -> sessions subscribed to that resort
    private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("resortID") String resortID) {
        session.getUserProperties().put("sender", new SessionSender(session));
        subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    @OnClose
    public void onClose(Session session, @PathParam("resortID") String resortID) {
        Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
    }

    @OnError
    public void onError(Session session, Throwable t, @PathParam("resortID") String resortID) {
        // log, then clean up the session
        logger.log(Level.WARNING, "WebSocket error on " + session.getId(), t);
        // clean up session
        if(resortID != null){
            Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
        }
        else{
            for(Set<Session> s : subscribers.values()){
                s.remove(session);
            }
        }
    }

    public static void Broadcast(String resortID, String json) {
        Set<Session> sessions = subscribers.get(resortID);
        if (sessions == null) {
            return;
        }
        for (Session s : sessions) {
            if (!s.isOpen()) {
                sessions.remove(s);
                continue;
            }
            SessionSender sender = (SessionSender) s.getUserProperties().get("sender");
            if (sender != null) {
                sender.Send(json);
            }
        }
    }
}