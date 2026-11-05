package consumer;

import com.google.gson.Gson;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Broadcaster {
    public static final String leaderboardExchange = "leaderboard.fanout";

    private final Channel channel;
    private final long intervalMs;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, LeaderboardMsg> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong publishCount = new AtomicLong(0);

    private static class LeaderboardMsg {
        String resortID;
        String dayID;
        String serverTimestamp;
        List<Rank> top;
    }

    private static class Rank {
        int rank;
        long skierID;
        long vertical;

        Rank(int rank, long skierID, long vertical) {
            this.rank = rank;
            this.skierID = skierID;
            this.vertical = vertical;
        }
    }

    public Broadcaster(Channel channel, long intervalMs) {
        this.channel = channel;
        this.intervalMs = intervalMs;
    }

    public void Start() throws IOException {
        channel.exchangeDeclare(leaderboardExchange, BuiltinExchangeType.FANOUT);
        timer.scheduleAtFixedRate(this::Flush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void Submit(String resortID, String dayID, List<Map.Entry<String, Long>> board) {
        LeaderboardMsg msg = new LeaderboardMsg();
        msg.resortID = resortID;
        msg.dayID = dayID;
        msg.top = new ArrayList<>();
        for (int i = 0; i < board.size(); i++) {
            Map.Entry<String, Long> e = board.get(i);
            msg.top.add(new Rank(i + 1, Long.parseLong(e.getKey()), e.getValue()));
        }
        pending.put(resortID + ":" + dayID, msg);
    }

    public long GetPublishCount() {
        return publishCount.get();
    }

    private void Flush() {
        for (String key : pending.keySet()) {
            LeaderboardMsg msg = pending.remove(key);
            if (msg == null) {
                continue;
            }
            msg.serverTimestamp = Instant.now().toString();
            try {
                byte[] body = gson.toJson(msg).getBytes(StandardCharsets.UTF_8);
                channel.basicPublish(leaderboardExchange, "", null, body);
                publishCount.incrementAndGet();
            } catch (Exception e) {
                System.out.println("publish leaderboard fail: " + e.getMessage());
            }
        }
    }
}
