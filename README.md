# SkiTrak

This project has a simple swipe server and a multithreaded load client.

The server is one WAR file running on Tomcat 9 on AWS EC2 (us-west-2). It has a POST endpoint for lift swipes, a health check endpoint, and a WebSocket endpoint that sends a fake leaderboard every 500 ms.

The client sends 500,000 swipes to the server. At the same time, 10 WebSocket clients listen to the leaderboard. At the end, the client prints throughput, latency, and connection results.

Folders:

- server: server code and how to deploy it
- client-part1: load client with basic results
- client-part2: load client with full performance analysis
- results: test outputs, CSV files, chart, and analysis

Each folder has its own README. All parts use Java 11 and Maven.