import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ChannelPool {
    private final Connection conn;
    private final int sz;
    private final BlockingQueue<Channel> bq;

    ChannelPool(Connection conn, int sz) {
        this.conn = conn;
        this.sz = sz;
        this.bq = new LinkedBlockingQueue<>(sz);
    }

    public void init() throws IOException {
        for(int i = 0; i < sz; i++) {
            Channel ch = conn.createChannel();
            ch.confirmSelect();
            bq.offer(ch);
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
        bq.offer(channel);
    }
}
