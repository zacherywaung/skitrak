import com.google.gson.JsonParser;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebListener
public class RabbitMQManager implements ServletContextListener {
    private static final Logger logger = Logger.getLogger(RabbitMQManager.class.getName());
    private static volatile Connection conn;          // connection to rabbitmq
    private static volatile ChannelPool pool;           // channel pool
    private static final int channelPoolSize = 32;

    private static final String deadLetterExchange = "skitrak.dlexchange";
    private static final String deadLetterQueue = "skitrak.dlqueue";
    public static final String ingestExchange = "skitrak.exchange";
    private static final String leaderboardExchange = "leaderboard.fanout";
    private static final int ttl = 600000;
    private static final int queueLen = 100000;
    public static final int resorts = 10;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("RABBIT_HOST", "localhost"));
        factory.setPort(5672);
        factory.setUsername(System.getenv().getOrDefault("RABBIT_USER", "guest"));
        factory.setPassword(System.getenv().getOrDefault("RABBIT_PASS", "guest"));
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        try {
            conn = factory.newConnection("swipe-ingest");

            conn.addShutdownListener(cause ->
                    logger.warning("RabbitMQ connection closed: " + cause.getMessage()));

            ((Recoverable) conn).addRecoveryListener(new RecoveryListener() {
                @Override
                public void handleRecoveryStarted(Recoverable r) {
                    logger.warning("RabbitMQ reconnecting...");
                }

                @Override
                public void handleRecovery(Recoverable r) {
                    logger.info("RabbitMQ reconnected");
                }
            });

            try (Channel channel = conn.createChannel()) {
                declareTopology(channel);
            }
            pool = new ChannelPool(conn, channelPoolSize);
            pool.init();
            StartLeaderboardListener();
            logger.info("Connected to RabbitMQ");
        } catch (Exception e) {
            throw new RuntimeException("Connect RabbitMQ Fail!!!", e);
        }
    }

    private static void declareTopology(Channel channel) throws IOException {
        // dead letter construct
        channel.exchangeDeclare(deadLetterExchange, BuiltinExchangeType.FANOUT, true);  // persist
        channel.queueDeclare(deadLetterQueue, true, false, false,null);    // persist, not belong to connection, no auto deletion, no rule
        channel.queueBind(deadLetterQueue, deadLetterExchange, "");                     // Exchange -> Queue
        // for swipe ingest
        channel.exchangeDeclare(ingestExchange, BuiltinExchangeType.TOPIC, true);
        Map<String, Object> rules = new HashMap<>();
        rules.put("x-message-ttl", ttl);
        rules.put("x-max-length", queueLen);
        rules.put("x-overflow", "reject-publish");
        rules.put("x-dead-letter-exchange", deadLetterExchange);

        for(int i = 1; i <= resorts; i++) {
            String qDeclare = "resort." + i;
            channel.queueDeclare(qDeclare, true, false, false, rules);
            channel.queueBind(qDeclare, ingestExchange, qDeclare);          // routing key resort.i -> queue resort.i
        }
    }

    private static void StartLeaderboardListener() throws IOException {
        Channel ch = conn.createChannel();
        ch.exchangeDeclare(leaderboardExchange, BuiltinExchangeType.FANOUT);
        String q = ch.queueDeclare().getQueue();
        ch.queueBind(q, leaderboardExchange, "");
        ch.basicConsume(q, true, (consumerTag, delivery) -> {
            String json = new String(delivery.getBody(), StandardCharsets.UTF_8);
            try {
                String resortID = JsonParser.parseString(json).getAsJsonObject().get("resortID").getAsString();
                LeaderboardEndpoint.Broadcast(resortID, json);
            } catch (Exception e) {
                logger.log(Level.WARNING, "bad leaderboard message: " + json, e);
            }
        }, consumerTag -> {});
        logger.info("Listening on " + leaderboardExchange + " with queue " + q);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Close RabbitMQ Fail!!!", e);
        }
    }

    public static Connection GetConnection() {
        return conn;
    }

    public static ChannelPool GetChannelPool() {
        return pool;
    }

    public static boolean IsConnected() {
        return conn != null && conn.isOpen();
    }
}