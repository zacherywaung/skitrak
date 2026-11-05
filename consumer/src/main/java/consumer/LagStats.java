package consumer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class LagStats {
    private static final int bucketCnt = 1000;
    private final AtomicLongArray buckets = new AtomicLongArray(bucketCnt + 1);
    private final AtomicLong lagSum = new AtomicLong(0);

    private long[] lastBuckets = new long[bucketCnt + 1];
    private long lastSum = 0;

    public void Add(long lag) {
        if (lag < 0) {
            lag = 0;
        }
        int index = (int) Math.min(lag, bucketCnt);
        buckets.incrementAndGet(index);
        lagSum.addAndGet(lag);
    }

    public synchronized String GetWindowSummary() {
        long[] now = Snapshot();
        long nowSum = lagSum.get();
        long[] window = new long[bucketCnt + 1];
        for (int i = 0; i <= bucketCnt; i++) {
            window[i] = now[i] - lastBuckets[i];
        }
        String ret = Summary(window, nowSum - lastSum);
        lastBuckets = now;
        lastSum = nowSum;
        return ret;
    }

    public String GetTotalSummary() {
        return Summary(Snapshot(), lagSum.get());
    }

    private long[] Snapshot() {
        long[] ret = new long[bucketCnt + 1];
        for (int i = 0; i <= bucketCnt; i++) {
            ret[i] = buckets.get(i);
        }
        return ret;
    }

    private static String Summary(long[] cnt, long sum) {
        long total = 0;
        for (long e : cnt) {
            total += e;
        }
        if (total == 0) {
            return "no swipes";
        }
        long target = (long) Math.ceil(95 / 100.0 * total);
        long acc = 0;
        int p95 = 0;
        for (int i = 0; i <= bucketCnt; i++) {
            acc += cnt[i];
            if (acc >= target) {
                p95 = i;
                break;
            }
        }
        int max = 0;
        for (int i = bucketCnt; i >= 0; i--) {
            if (cnt[i] > 0) {
                max = i;
                break;
            }
        }
        return String.format("lag mean = %.2f ms, P95 = %s ms, max = %s ms",
                (double) sum / total, ToStr(p95), ToStr(max));
    }

    private static String ToStr(int index) {
        return index == bucketCnt ? ">=" + bucketCnt : String.valueOf(index);
    }
}