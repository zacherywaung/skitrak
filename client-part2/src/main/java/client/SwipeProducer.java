package client;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
//skierID: random 1–100000
//liftID: random 1–40
//time: random 1–360

public class SwipeProducer implements Runnable{
    private final BlockingQueue<SwipeInfo> bq;   // blocking queue buffer
    private final int total;                        // all Swipe to create
    private final int resorts;                     // random 1–10 (configurable)
    private final int seasonID;                     // fixed for run (ex. 2026)
    private final int dayID;                        // fined for run (represent today)

    public SwipeProducer(BlockingQueue<SwipeInfo> bq, int total, int resorts, int seasonID, int dayID) {
        if (total <= 0 || resorts <= 0) {
            throw new IllegalArgumentException("total and resorts upperbound should be positive");
        }
        this.bq = bq;
        this.total = total;
        this.resorts = resorts;
        this.seasonID = seasonID;
        this.dayID = dayID;
    }

    // SwipeInfo(int skierID, int resortID, int liftID, int seasonID, int dayID, int time)
    @Override
    public void run() {
        Random r = new Random();
        try {
            for(int i = 0; i < total; i++) {
                SwipeInfo swipe = new SwipeInfo(
                        r.nextInt(100000) + 1,
                        r.nextInt(resorts) + 1,
                        r.nextInt(40) + 1,
                        seasonID,
                        dayID,
                        r.nextInt(360) + 1
                );
                bq.put(swipe);
            }
        }catch(InterruptedException e) {}
    }
}
