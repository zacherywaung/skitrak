# consumer — Aggregation + leaderboard broadcaster

Standalone Java program. Reads swipes from the 10 resort queues, keeps each skier's total vertical and a top-20 leaderboard per resort and day, and publishes changed leaderboards to `leaderboard.fanout`.

## How it works

- One connection, K channels (default 10); `resort.i` is consumed by channel `(i - 1) % K`, so each resort is processed in order.
- Manual ack after aggregation, prefetch 50. Unparseable messages are nacked to the dead-letter queue.
- Duplicates skipped by `eventId`.
- Ranking: higher total first, ties by smaller skierID.
- Changed boards are coalesced (latest per resort:day) and published every 200 ms by one thread.
- Every 5 s it prints processed count, broadcasts, and consumer lag (mean / P95 / max).

## Classes

| Class | Role |
|---|---|
| `ConsumerApp` | entry point: connection, channels, callbacks, health server, stats |
| `SwipeInfo` | parsed queue message |
| `Aggregator` | dedupe, per-skier totals, per-resort-day `TopN` |
| `TopN` | top-20 list for one resort and day |
| `Broadcaster` | coalescing publisher to `leaderboard.fanout` |
| `LagStats` | 1 ms bucket histogram of consumer lag |

## Configuration (environment variables)

| Variable | Default |
|---|---|
| `RABBIT_HOST` | localhost |
| `RABBIT_USER` / `RABBIT_PASS` | guest / guest |
| `CONSUMER_THREADS` | 10 |
| `PREFETCH` | 50 |
| `BROADCAST_INTERVAL_MS` | 200 |
| `HEALTH_PORT` | 8081 |

## Build and run

```bash
mvn clean package                      # target/consumer.jar (dependencies included)
java -jar target/consumer.jar
curl localhost:8081/health
```

On EC2 it runs under systemd; see `deployment/skitrak-consumer.service`.