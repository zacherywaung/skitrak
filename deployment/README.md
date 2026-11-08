# Deployment

All instances run Amazon Linux 2023 in us-west-2 (default VPC). Services find RabbitMQ by its private IP.

| Machine | Type | Runs |
|---|---|---|
| skitrak-rabbitmq | micro | RabbitMQ 3.13 in Docker (`rabbitmq-setup.sh`) |
| skitrak-consumer | micro | `consumer.jar` under systemd (`skitrak-consumer.service`) |
| skitrak-server-1 … 4 | micro | Tomcat 9 + `ski-servlet.war` under systemd (`tomcat.service`) |
| skitrak-client | t3.medium | `client.jar`, started by `monitoring/run_remote.sh` |
| skitrak-alb | ALB | see `alb.md` |

## Files

| File | Where it goes |
|---|---|
| `rabbitmq-setup.sh` | run once on the RabbitMQ instance |
| `consumer.env.example` | `/etc/skitrak-consumer.env` on the consumer (real password, `chmod 600`) |
| `skitrak-consumer.service` | `/etc/systemd/system/` on the consumer |
| `server-setenv.sh.example` | `/opt/tomcat9/bin/setenv.sh` on each server |
| `tomcat.service` | `/etc/systemd/system/` on each server |
| `alb.md` | ALB and target group settings |
| `security-groups.md` | inbound rules for every group |

## Setup order

1. RabbitMQ: launch, run `rabbitmq-setup.sh`.
2. Consumer: install Java 11 (`dnf install java-11-amazon-corretto-headless`), copy `consumer.jar` to `/opt/consumer/`, add the env file and service, `systemctl enable --now skitrak-consumer`.
3. Server 1: Tomcat 9 + Java 11, add `setenv.sh`, deploy `ski-servlet.war` to `webapps/`, add the service, `systemctl enable --now tomcat`.
4. Servers 2–4: create an AMI from server 1 and launch copies. `SERVER_ID=$(hostname)` gives each copy a unique id.
5. ALB and target group per `alb.md`.
6. Client: install Java 11, copy `client.jar`, allow its public IP in `skitrak-alb-sg`.

## Restart order

Start RabbitMQ first and wait until the management UI loads, then start the servers and the consumer.
The consumer exits and is restarted by systemd until RabbitMQ and the queues exist.
A server that boots before RabbitMQ fails to deploy (HTTP 404); fix with `sudo systemctl restart tomcat`.