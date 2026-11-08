# client-v2 — Load client for Assignment 2

Copy of `client-part2` with these changes:

- Target, load size and thread count come from environment variables.
- Cookie handling is disabled, so ALB stickiness does not pin all swipes to one server.
- After sending, it waits until no leaderboard arrives for 3 s before closing subscribers.
- Results go to `results-v2/`: `allswipes.csv` (adds skierID, liftID), `throughput_over_time.csv`, `final_leaderboards.txt` (last board per resort).
- Packaged as a runnable jar.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SKITRAK_HOST` | localhost:8080 | host[:port]; the ALB DNS name for the tests |
| `TOTAL_SWIPES` | 500000 | swipes to send |
| `THREADS` | 1024 | main-phase thread pool size |

## Build and run

```bash
mvn clean package                      # target/client.jar
mkdir -p results-v2
SKITRAK_HOST=<alb-dns> THREADS=512 TOTAL_SWIPES=500000 java -jar target/client.jar
```

The tests ran this jar on an EC2 instance in us-west-2, started from a laptop with `monitoring/run_remote.sh`.