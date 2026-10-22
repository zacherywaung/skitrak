import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebListener
public class RabbitMQManager implements ServletContextListener {
    private static final Logger logger = Logger.getLogger(RabbitMQManager.class.getName());
    private static volatile Connection conn;          // connection to rabbitmq

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        try {
            conn = factory.newConnection("swipe-ingest");
            logger.info("Connected to RabbitMQ");
        } catch (Exception e) {
            throw new RuntimeException("Connect RabbitMQ Fail!!!", e);
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

}
