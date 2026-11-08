# monitoring

## queue_monitor.py

Polls the RabbitMQ management API every second and writes one CSV row per sample: total depth, ready, unacked, publish rate and ack rate summed over `resort.1`–`resort.10`, plus the dead-letter queue count. On Ctrl+C it prints peak/average depth and publish/ack rates over active samples. Standard library only.

```bash
export RABBIT_PASS=<password>
python monitoring/queue_monitor.py --host <rabbitmq-public-ip> --out results-v2/run1/queue.csv
```

RabbitMQ refreshes these statistics every 5 s, so values change in 5 s steps.

## run_remote.sh

Runs the load client on the client EC2 over SSH (output streams to the local terminal), then copies its results into `results-v2/<run_name>/`.

```bash
export CLIENT=<client-ec2-public-ip>
export ALB=<alb-dns-name>
./monitoring/run_remote.sh run1 512 500000
```

Arguments: run name, client threads, total swipes.