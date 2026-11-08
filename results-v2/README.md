# results-v2 — Assignment 2 test data

`results.md` holds all result tables (main runs, thread sweeps, failover).

## Runs

| Folder | Servers | Client threads | Consumer threads | Swipes |
|---|---|---|---|---|
| `run1` | 1 | 512 | 10 | 500K |
| `run2` | 2 | 512 | 10 | 500K |
| `run3` | 4 | 512 | 10 | 500K |
| `run4` | 4 | 512 | 10 | 1M |
| `threads64`, `threads128`, `threads256` | 4 | 64 / 128 / 256 | 10 | 500K |
| `consumer5`, `consumer15` | 4 | 512 | 5 / 15 | 500K |
| `failover` | 4 (one stopped mid-run) | 512 | 10 | 1M |

Each folder contains:

- `client_output.txt` — full client console output
- `allswipes.csv` — one row per swipe: timestamp, latency, status, resortID, skierID, liftID
- `throughput_over_time.csv` — throughput per 10 s
- `final_leaderboards.txt` — last leaderboard received per resort
- `queue.csv` — RabbitMQ samples from `monitoring/queue_monitor.py` (not for thread-count runs)

## screenshots/

| File | Shows |
|---|---|
| `runN_client_1/2.png` | client output, runs 1–4 |
| `runN_rabbitMQ.png` | RabbitMQ queue depth and message rates, runs 1–4 |
| `alb_distribution.png` | NetworkIn per server (ALB request distribution) |
| `cpu_all_runs.png` | CPU of all instances across runs 1–4 |
| `leaderboard_evidence.png` | three swipes and the leaderboards they produced |
| `failover_unhealthy.png` | target group with the stopped server unhealthy |
| `config_*.png` | health check, stickiness and idle timeout settings |