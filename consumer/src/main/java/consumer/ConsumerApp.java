package consumer;

import com.google.gson.Gson;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class ConsumerApp {
    private static final int resorts = 10;
    private static final int topN = 20;
    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        Connection conn = factory.newConnection("swipe-consumer");
        Channel channel = conn.createChannel();

        Aggregator aggregator = new Aggregator(topN);

        DeliverCallback onMessage = (consumerTag, delivery) -> {
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
                    if (board != null) {
                        System.out.println("resort " + swipe.GetResortID() + " day " + swipe.GetDayID()
                                + " top changed: " + board);
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

        // subscribe to all 10 queue
        for (int i = 1; i <= resorts; i++) {
            channel.basicConsume("resort." + i, false, onMessage, consumerTag -> {});
        }
        System.out.println("waiting for messages on resort.1 ~ resort." + resorts + " ...");
    }
}