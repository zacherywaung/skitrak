# SkiTrak

This project is a ski lift swipe system with a live leaderboard, built for CS6650.

Assignment 1 built a dummy swipe server and a multithreaded load client. Assignment 2 turns it into a real pipeline on AWS EC2 (us-west-2). Swipes are sent to ingest servers behind a load balancer. The servers publish each swipe to RabbitMQ. A consumer reads the swipes, adds up each skier's vertical, and keeps a top-20 leaderboard for each resort. Leaderboards go back to every server through a RabbitMQ fanout exchange, and each server pushes them to its WebSocket subscribers.

Assignment 2 folders:

- server-v2: ingest and WebSocket server, publishes swipes to RabbitMQ
- consumer: reads swipes, builds leaderboards, and broadcasts them
- client-v2: load client used for the Assignment 2 tests
- deployment: RabbitMQ setup, systemd services, ALB and security group settings
- monitoring: queue monitor script and remote test runner
- results-v2: test outputs, result tables, and screenshots
- docs/diagrams: system architecture, swipe-to-leaderboard sequence, and queue topology diagrams
- AI_USAGE.md: AI usage reflection

Assignment 1 folders:

- server: dummy swipe server with a fake leaderboard
- client-part1: load client with basic results
- client-part2: load client with full performance analysis
- results: Assignment 1 test outputs and analysis

Each folder has its own README. All parts use Java 11 and Maven.