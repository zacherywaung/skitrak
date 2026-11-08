# ALB Configuration (us-west-2)

Created in the AWS console; this file records the settings.

## Load balancer: `skitrak-alb`

| Setting | Value |
|---|---|
| Type | Application Load Balancer |
| Scheme | Internet-facing, IPv4 |
| Availability zones | all default subnets |
| Security group | `skitrak-alb-sg` |
| Listener | HTTP :80 → forward to `skitrak-servers-tg` |
| Connection idle timeout | 300 s (default 60 s; long-lived WebSocket connections) |

WebSocket is supported natively by the ALB; no extra setting is needed.

## Target group: `skitrak-servers-tg`

| Setting | Value |
|---|---|
| Target type | Instances |
| Protocol / port | HTTP / 8080 (Tomcat) |
| Targets | `skitrak-server-1` … `skitrak-server-4` (registered as each test needs) |
| Health check path | `/ski-servlet/health` |
| Interval / timeout | 30 s / 5 s |
| Healthy / unhealthy threshold | 2 / 3 |
| Success codes | 200 |
| Stickiness | on, load-balancer generated cookie, 1 day |
| Deregistration delay | 30 s (default 300 s) |
| Algorithm | round robin |

## Notes

- The health check returns 503 when a server has lost its RabbitMQ connection, so the ALB stops sending it traffic.
- The load client disables cookie handling, so swipe POSTs are spread round robin. Stickiness matters for WebSocket subscribers.
- With all targets unhealthy, the ALB fails open and routes to all targets.