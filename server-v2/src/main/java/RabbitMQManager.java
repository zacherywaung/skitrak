import com.rabbitmq.client.*;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.io.IOException;
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
    private static final int ttl = 600000;
    private static final int queueLen = 100000;
    public static final int resorts = 10;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        try {
            conn = factory.newConnection("swipe-ingest");
            // connection shutdown printing
            conn.addShutdownListener(cause ->
                    logger.warning("RabbitMQ connection closed: " + cause.getMessage()));
            // connection recover
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
            channel.queueBind(qDeclare, ingestExchange, qDeclare);          // exchange get msg routingkey 3, send to 1
        }
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

}
