# Results

Test setup: the server ran on an EC2 t2.micro (1 vCPU) in us-west-2. The client ran on my MacBook in New York. The round trip time between them was about 82 ms.

Files in this folder:

- rtt_measurement.txt: the curl results used for the Little's Law prediction
- clock_offset.txt: clock checks on EC2 (chronyc) and on my Mac (sntp)
- part1_final.txt and part2_final.txt: the final runs with 1024 threads
- thread_tuning: runs with 128, 256, 512, 1024, and 1536 threads
- allswipes.csv and throughput_over_time.csv: data from the final Part 2 run
- throughput_over_time.png: the throughput chart

## Little's Law prediction

Using curl -w on ten POSTs from New York to EC2 (us-west-2), time_connect gives the TCP setup time and time_total − time_connect gives the request time. Medians: C ≈ 86 ms, W ≈ 82 ms.（showed in /results/rtt_measurement.txt）
Warmup: L = 32, each thread pays C for connection(86 ms) and run 1000 requests
λ = L / W, λ = 32 / (0.082) = 390 / s, if we don’t reuse connection, it would be λ = 32 / (W + C) = 190 / s.
Main Phase: λ = L / 0.082, ex. 1,561/s at 128 threads and 12,488/s at 1,024 threads. This assumes each request still takes 82 ms; once the server or client cannot keep up, requests queue, W grows, and throughput stops increasing linearly.
 

## Measured vs. predicted

| Threads | Predicted | Real | Median latency(ms) |
|---|---|-----|--------------------|
| 32 (warmup) | 390 | 356 | -                  |
| 128 | 1,561 | 1,417 | 80.5               |
| 256 | 3,122 | 2,555 | 80.9               |
| 512 | 6,244 | 4,769 | 80.8               |
| 1024 | 12,488 | 8,338| 92.5               |
| 1536 | 18,732 | 8,514| 137.1              |

I ran each setting twice, and the results were within 5% of each other.

## Why real throughput is lower

1. The server gets busy. Up to 512 threads, the median latency stays at about 81 ms, which is just the network round trip. At 1,024 threads and above, the median latency goes up, but throughput almost stops growing. This means requests are waiting on the server. The server only has 1 vCPU, and Tomcat uses 200 worker threads by default. It seems to reach its limit at about 10,000 requests per second.
2. Connections are closed often. Tomcat closes a connection after 100 requests by default. So each run opens about 5,000 new connections, and each new connection adds about 86 ms.
3. My laptop has a thread limit. With 2,048 threads, the client crashed with unable to create native thread, because macOS limits how many threads one process can have.


## Push latency and clock skew

Push latency is the receive time on my laptop minus the serverTimestamp from the server. These two times come from different clocks, so the result includes the difference between the clocks.

I checked both clocks. The EC2 clock was almost exact (less than 0.01 ms off). My Mac was about 30.7 ms slow (plus or minus 10 ms), and this stayed the same before and after the run. The measured median push latency was about 10 ms. After adding the 30.7 ms back, it is about 41 ms. This is about half of the round trip time, which is what we expect for a one-way message.

Before I synced my Mac's clock, some runs even showed negative push latency (median as low as -26 ms). This clearly shows the effect of clock skew.