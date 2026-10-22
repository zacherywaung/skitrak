package client;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;

public class ConnectionMonitor implements Runnable{
    private PoolingHttpClientConnectionManager manager;
    private final long interval;
    private int peak;

    ConnectionMonitor(PoolingHttpClientConnectionManager manager, long interval) {
        this.manager = manager;
        this.interval = interval;
    }

    @Override
    public void run() {
        try {
            while(true) {
                monitor();
                Thread.sleep(interval);
            }
        } catch(InterruptedException ex) {
            monitor();
        }
    }

    private void monitor() {
        PoolStats stats = manager.getTotalStats();
        int current = stats.getLeased() + stats.getAvailable();
        if(current > peak) {
            peak = current;
        }
    }

    public int GetPeak() {
        return peak;
    }
}
