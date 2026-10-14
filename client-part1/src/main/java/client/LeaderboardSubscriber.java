package client;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ClientEndpoint
public class LeaderboardSubscriber {
    private final String partition = "\"serverTimestamp\":\"";
    private final int resortID;
    private final String uri;
    private final WebSocketContainer container;
    private Session session;
    private double connectTime;
    private final List<Double> latencies = Collections.synchronizedList(new ArrayList<>());

    public LeaderboardSubscriber(int resortID, String uri, WebSocketContainer container) {
        this.resortID = resortID;
        this.uri = uri;
        this.container = container;
    }

    public void connect() throws Exception {
        long startTime = System.nanoTime();
        session = container.connectToServer(this, URI.create(uri));
        connectTime = (System.nanoTime() - startTime) / 1000000.0;
    }

    public void close() throws IOException{
        session.close();
    }

    @OnOpen
    public void onOpen(Session session) {
        System.out.println("Resort" + resortID + ": connected!!!");
    }

//    {
//        "resortID": "7",
//            "serverTimestamp": "2026-01-20T18:03:12.481Z",
//            "top": [
//        { "rank": 1, "skierID": 40213, "vertical": 44120 },
//        { "rank": 2, "skierID": 11872, "vertical": 43880 }
//        ]
//    }
    @OnMessage
    public void onMessage(String message) {
        Instant recvTime = Instant.now();
        int begin = message.indexOf(partition);
        if(begin < 0) {
            return;
        }
        begin += partition.length();
        int end = message.indexOf('"', begin);
        Instant serverTime = Instant.parse(message.substring(begin, end));
        double latency = Duration.between(serverTime, recvTime).toNanos() / 1000000.0;
        latencies.add(latency);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) throws IOException{
        System.out.println("Resort" + resortID + ": closed!!!");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        System.out.println("Resort: " + resortID + " error: " + t.getMessage());
    }

    public int GetResortID() {
        return resortID;
    }

    public int GetMsgCount() {
        return latencies.size();
    }

    public double GetConnectTime() {
        return connectTime;
    }

    public List<Double> GetLatencies() {
        return new ArrayList<>(latencies);
    }
}
