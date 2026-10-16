# Client Part 1

This client sends 500,000 swipes to the server and watches the leaderboard with 10 WebSocket clients.

How it works:

- One producer thread creates all the swipes and puts them in a queue.
- Warmup: 32 threads each send 1,000 swipes, then stop. This part is timed on its own.
- Main phase: a thread pool sends the other 468,000 swipes, 100 swipes per task.
- All threads share one HTTP connection pool (Apache HttpClient). A failed swipe is retried up to 5 times with exponential backoff.
- One WebSocket client per resort connects before warmup and closes after the main phase. If a connection drops, it reconnects.

## Settings

The settings are at the top of src/main/java/client/Client.java. The main ones are:

- baseUrl: the server address, for example http://<EC2_IP>:8080/ski-servlet
- threadCount: the thread pool size for the main phase (I used 1024)
- batchSize: swipes per task in the main phase (100)

## Run

Start the server first. Then run the main method in Client.java from IntelliJ, or run this from the project root:

```
mvn -q -f client-part1/pom.xml compile exec:java -Dexec.mainClass=client.Client
```

When it finishes, it prints the number of successful and failed swipes, the run time and throughput (warmup and main phase separately), connection stats, and the WebSocket results.

Note: on macOS, 2048 threads did not work because of the per-process thread limit.