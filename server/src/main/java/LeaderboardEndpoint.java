import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
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
    private static final ScheduledExecutorService pusher =
            Executors.newSingleThreadScheduledExecutor();

    static {
        // Dummy: every 500ms, push a synthetic top-N to each resort's subscribers.
        pusher.scheduleAtFixedRate(LeaderboardEndpoint::pushAll, 500, 500, TimeUnit.MILLISECONDS);
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("resortID") String resortID) {
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

    private static String syntheticLeaderboard(String resortID) {
        return "{\"resortID\":\"" + resortID + "\",\"serverTimestamp\":\""
                + Instant.now() + "\",\"top\":[" +
                "{\"rank\":1,\"skierID\":1,\"vertical\":10000}" +
                ",{\"rank\":2,\"skierID\":2,\"vertical\":9999}" +
                "]}";
    }

    private static void pushAll() {
        for (Map.Entry<String, Set<Session>> e : subscribers.entrySet()) {
            String json = syntheticLeaderboard(e.getKey()); // canned/random top-N + serverTimestamp
            for (Session s : e.getValue()) {
                if (!s.isOpen()) {
                    e.getValue().remove(s);
                    continue;
                }
                try {
                    s.getAsyncRemote().sendText(json);
                }catch (Exception ex) {
                    logger.log(Level.WARNING, "push failed on " + s.getId(), ex);
                }
            }
        }
    }
}