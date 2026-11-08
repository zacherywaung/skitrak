| Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms) |
|---|---|---|---|---|---|---|---|---|-------------------------------------------|
| 1 | 1 | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 |
| 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 |
| 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 |
| 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |


## Client thread sweep (4 servers)

| Client Threads | Throughput (req/s) | p50 Latency (ms) |
|---|--------------------|------------------|
| 64 | 4742               | 12.49            |
| 128 | 5882               | 19.51            |
| 256 | 5910               | 28.47            |
| 512 | 6024               | 37.22            |


## Consumer thread sweep (4 servers, 512 client threads)

| Consumer Threads | Throughput (req/s) | Peak / Avg Queue Depth | Consumer Lag p95 (ms) |
|---|--------------------|------------------------|-----------------------|
| 5 | 5975               | 88/58.8                | 207                   |
| 10 | 6024               | 98 / 59.7              | 210                   |
| 15 | 5626               | 87/64.9                | 227                   |

## Failover test (4 servers, 512 client threads, 1M swipes, Tomcat on server-2 stopped ~30s into the run)

- Throughput: 5839 req/s (vs 6039 without failure, -3%)
- Retries / Failed: 155552 / 127
- Time until server-2 marked unhealthy: approximitelly 50s
- Subscriber reconnects: 3 (resorts 1, 7, 8, which were connected to server-2)
- Consumer processed / DLQ: 999873 (= 1,000,000 - 127 failed) / 0
- Peak / Avg queue depth: 2380 / 118.9 (short spike at failure time)