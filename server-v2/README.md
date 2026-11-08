# server-v2 — Ingest + WebSocket server

Tomcat 9 web app (`ski-servlet.war`). Each instance accepts swipes, publishes them to RabbitMQ, and pushes leaderboards to its own WebSocket subscribers.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/ski-servlet/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}` | ingest a swipe; 201 after the broker confirms, 400 invalid, 503 queue unavailable |
| GET | `/ski-servlet/health` | 200 when connected to RabbitMQ, else 503; returns `serverId` |
| WS | `/ski-servlet/leaderboard/{resortID}` | live top-20 leaderboard for a resort |

## Classes

| Class | Role |
|---|---|
| `SkierServlet` | validate (resortID 1–10, time 1–360, liftID 1–40), build the event, publish with confirm |
| `RabbitMQManager` | `@WebListener`: connection, topology, channel pool, leaderboard listener |
| `ChannelPool` | 32 confirm-mode channels in a `BlockingQueue`; replaces closed channels |
| `LiftVerticals` | loads `lift_vertical.json` (liftID → vertical) |
| `model/SwipeInfo` | queue message (eventId, resortID, dayID, skierID, liftID, vertical, time, timestamp, serverId) |
| `LeaderboardEndpoint` | resortID → local sessions; `Broadcast` pushes a board |
| `SessionSender` | one per session; one async send at a time, keeps only the latest pending board |
| `HealthChecker` | `/health` |

## Configuration (environment variables)

| Variable | Default |
|---|---|
| `RABBIT_HOST` | localhost |
| `RABBIT_USER` / `RABBIT_PASS` | guest / guest |
| `SERVER_ID` | local |

On EC2 these are set in `/opt/tomcat9/bin/setenv.sh` (see `deployment/`).

## Build and deploy

```bash
mvn clean package            # target/ski-servlet.war
cp target/ski-servlet.war /opt/tomcat9/webapps/
```