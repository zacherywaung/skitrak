# Security Groups (us-west-2, default VPC)

All groups keep the default outbound rule: All traffic → 0.0.0.0/0.
"My IP" means the developer laptop's public IPv4 (/32).

## skitrak-alb-sg
| Port | Source | Purpose |
|---|---|---|
| 80 | My IP | manual testing through the ALB |
| 80 | client EC2 public IP /32 | load client → ALB |

The client EC2 reaches the internet-facing ALB by its public IP, so the rule uses that IP, not a security-group reference.

## skitrak-server-sg
| Port | Source | Purpose |
|---|---|---|
| 22 | My IP | SSH |
| 8080 | My IP | direct testing of one server |
| 8080 | skitrak-alb-sg | ALB → Tomcat |

## skitrak-rabbitmq-sg
| Port | Source | Purpose |
|---|---|---|
| 22 | My IP | SSH |
| 15672 | My IP | management UI and queue_monitor.py |
| 5672 | skitrak-server-sg | AMQP from ingest servers |
| 5672 | skitrak-consumer-sg | AMQP from consumer |

## skitrak-consumer-sg
| Port | Source | Purpose |
|---|---|---|
| 22 | My IP | SSH |
| 8081 | My IP | consumer health check |

## skitrak-client-sg
| Port | Source | Purpose |
|---|---|---|
| 22 | My IP | SSH (run_remote.sh) |