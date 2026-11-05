package consumer;

import com.google.gson.Gson;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConsumerApp {
    private static final int resorts = 10;
    private static final int topN = 20;
    private static final int consumerThreads = Integer.parseInt(System.getenv().getOrDefault("CONSUMER_THREADS", "10"));
    private static final int prefetch = Integer.parseInt(System.getenv().getOrDefault("PREFETCH", "50"));
    private static final long broadcastIntervalMs = Long.parseLong(System.getenv().getOrDefault("BROADCAST_INTERVAL_MS", "200"));
    private static final int healthPort = Integer.parseInt(System.getenv().getOrDefault("HEALTH_PORT", "8081"));
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(System.getenv().getOrDefault("RABBIT_HOST", "localhost"));
            factory.setPort(5672);
            factory.setUsername(System.getenv().getOrDefault("RABBIT_USER", "guest"));
            factory.setPassword(System.getenv().getOrDefault("RABBIT_PASS", "guest"));
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5000);
            ExecutorService workers = Executors.newFixedThreadPool(consumerThreads);   // callbacks run on these K threads
            Connection conn = factory.newConnection(workers, "swipe-consumer");

            Aggregator aggregator = new Aggregator(topN);
            Broadcaster broadcaster = new Broadcaster(conn.createChannel(), broadcastIntervalMs);   // its own channel
            broadcaster.Start();
            LagStats lagStats = new LagStats();

            // K channels
            Channel[] channels = new Channel[consumerThreads];
            for (int t = 0; t < consumerThreads; t++) {
                channels[t] = conn.createChannel();
                channels[t].basicQos(prefetch);
            }

            // resort.i -> channel (i - 1) % K
            for (int i = 1; i <= resorts; i++) {
                Channel ch = channels[(i - 1) % consumerThreads];
                ch.basicConsume("resort." + i, false, OnMessage(ch, aggregator, broadcaster, lagStats), consumerTag -> {});
            }
            System.out.println(consumerThreads + " consumer threads, wait for msg on resort.1 - resort." + resorts);

            HttpServer healthServer = HttpServer.create(new InetSocketAddress(healthPort), 0);
            healthServer.createContext("/health", httpExchange -> {
                boolean isOk = conn.isOpen();
                String body = "{\"status\":\"" + (isOk ? "ok" : "rabbitmq disconnected") + "\",\"processed\":"
                        + aggregator.GetSwipesProcessed() + ",\"consumerThreads\":" + consumerThreads
                        + ",\"lag\":\"" + lagStats.GetTotalSummary() + "\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                httpExchange.getResponseHeaders().set("Content-Type", "application/json");
                httpExchange.sendResponseHeaders(isOk ? 200 : 503, bytes.length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            healthServer.start();
            System.out.println("health check on port " + healthPort);

            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                    () -> System.out.println("processed: " + aggregator.GetSwipesProcessed()
                            + ", broadcasts: " + broadcaster.GetPublishCount()
                            + ", " + lagStats.GetWindowSummary()),
                    5, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("consumer start fail: " + e.getMessage());
            System.exit(1);
        }
    }

    private static DeliverCallback OnMessage(Channel channel, Aggregator aggregator, Broadcaster broadcaster, LagStats lagStats) {
        return (consumerTag, delivery) -> {
            long tag = delivery.getEnvelope().getDeliveryTag();     // this message's number on this channel
            String json = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                // parse
                SwipeInfo swipe = gson.fromJson(json, SwipeInfo.class);
                if (swipe == null || swipe.GetEventId() == null || swipe.GetResortID() == null
                        || swipe.GetDayID() == null || swipe.GetSkierID() == null) {
                    throw new IllegalArgumentException("missing fields");
                }
                // idempotent
                if (aggregator.IsProcessed(swipe.GetEventId())) {
                    System.out.println("duplicate, skipped: " + swipe.GetEventId());
                } else {
                    // update topN
                    List<Map.Entry<String, Long>> board = aggregator.Process(swipe);
                    if (swipe.GetTimestamp() != null) {
                        lagStats.Add(System.currentTimeMillis() - Instant.parse(swipe.GetTimestamp()).toEpochMilli());
                    }
                    if (board != null) {
                        broadcaster.Submit(swipe.GetResortID(), swipe.GetDayID(), board);
                    }
                }
                // ack
                channel.basicAck(tag, false);
            } catch (Exception e) {
                // error:  reject, move into deadletter queue
                System.out.println("bad message, sent to dead-letter queue: " + json);
                channel.basicNack(tag, false, false);
            }
        };
    }
}