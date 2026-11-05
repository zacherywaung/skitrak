# Security Groups (us-west-2)

All three groups keep the default outbound rule: All traffic -> 0.0.0.0/0.

## skitrak-rabbitmq-sg
| Port  | Source               | Purpose                         |
|-------|----------------------|---------------------------------|
| 22    | My IP                | SSH                             |
| 15672 | My IP                | RabbitMQ management UI          |
| 5672  | skitrak-server-sg    | AMQP from ingest servers        |
| 5672  | skitrak-consumer-sg  | AMQP from consumer              |

## skitrak-server-sg
| Port  | Source               | Purpose                         |
|-------|----------------------|---------------------------------|
| 22    | My IP                | SSH                             |
| 8080  | My IP                | direct testing before ALB       |

## skitrak-consumer-sg
| Port  | Source               | Purpose                         |
|-------|----------------------|---------------------------------|
| 22    | My IP                | SSH                             |
| 8081  | My IP                | consumer health check           |