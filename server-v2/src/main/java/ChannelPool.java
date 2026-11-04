import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ChannelPool {
    private final Connection conn;
    private final int sz;
    private final BlockingQueue<Channel> bq;
    private static final Logger logger = Logger.getLogger(ChannelPool.class.getName());

    ChannelPool(Connection conn, int sz) {
        this.conn = conn;
        this.sz = sz;
        this.bq = new LinkedBlockingQueue<>(sz);
    }

    public void init() throws IOException {
        for(int i = 0; i < sz; i++) {
            bq.offer(newChannel());
        }
    }
    public Channel borrowChannel(long timeout) throws InterruptedException, TimeoutException{
        Channel channel = bq.poll(timeout, TimeUnit.MILLISECONDS);
        if(channel == null) {
            throw new TimeoutException("Channel in channel pool stuck for " + timeout + "ms!!!");
        }
        return channel;
    }
    public void returnChannel(Channel channel) {
        // channel closed, connection ok: replace it
        // connection down, keep old one, recovery will reopen it
        if(!channel.isOpen() && conn.isOpen()) {
            try {
                channel = newChannel();
            } catch (IOException e) {
                logger.log(Level.WARNING, "replace channel error", e);
                return;
            }
        }
        if (!bq.offer(channel)) {
            logger.warning("ChannelPool is full, closing extra channel");
            try {
                channel.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "close extra channel fail", e);
            }
        }
    }

    private Channel newChannel() throws IOException {
        Channel ch = conn.createChannel();
        ch.confirmSelect();
        return ch;
    }
}
