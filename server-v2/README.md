# Server

The server is one WAR file (ski-servlet.war) running on Tomcat 9. It has three endpoints:

- POST /ski-servlet/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
  Records one lift swipe. The body looks like {"time": 217, "liftID": 21}. It returns 201 if the swipe is valid, and 400 if not. All IDs must be positive, time must be 1 to 360, and liftID must be 1 to 40.
- GET /ski-servlet/health
  Returns {"status":"ok"}.
- ws://<host>:8080/ski-servlet/leaderboard/{resortID}
  WebSocket. Every 500 ms, the server sends a fake leaderboard with a serverTimestamp to everyone watching that resort.

Main classes: SkierServlet handles swipes, HealthChecker handles the health check, and LeaderboardEndpoint handles the WebSocket connections.

## Build

```
cd server
mvn clean package
```

This creates target/ski-servlet.war.

## Deploy to EC2

I used a t2.micro instance in us-west-2 with Java 11 and Tomcat 9 installed in /opt/tomcat9. The security group needs to allow port 22 (SSH) and port 8080 (Tomcat) from your IP.

```
scp -i <key.pem> target/ski-servlet.war ec2-user@<EC2_IP>:/tmp/
ssh -i <key.pem> ec2-user@<EC2_IP>
cp /tmp/ski-servlet.war /opt/tomcat9/webapps/
/opt/tomcat9/bin/startup.sh
```

You can check the log at /opt/tomcat9/logs/catalina.out to make sure the WAR was deployed.

## Test

```
curl -i -X POST http://<EC2_IP>:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123 -H "Content-Type: application/json" -d '{"time":217,"liftID":21}'
curl -i http://<EC2_IP>:8080/ski-servlet/health
wscat -c ws://<EC2_IP>:8080/ski-servlet/leaderboard/1
```

The first command should return 201, the second should return {"status":"ok"}, and the third should print a new message every 500 ms.