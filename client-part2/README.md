# Client Part 2

This client does the same thing as Part 1, but also records every request for performance analysis.

What it adds:

- For every swipe, it records the send time, latency, status code, and resortID.
- At the end, it prints the ingest latency (mean, median, p95, p99, min, max), the throughput for each resort, and the throughput for every 10 seconds.
- It also prints the push latency for the WebSocket messages (mean, median, p95, p99, min, max) and the total number of leaderboard updates.

Latency is measured with System.nanoTime(). The CSV timestamps and the push latency use System.currentTimeMillis(), because push latency compares my clock with the server's serverTimestamp.

## Settings

Same as Part 1, at the top of src/main/java/client/Client.java.

## Run

Run it from the project root, because the CSV files are written to the results folder. In IntelliJ this is the default. From the command line:

```
mvn -q -f client-part2/pom.xml compile exec:java -Dexec.mainClass=client.Client
```

## Output files

- results/allswipes.csv: one line per swipe, with the columns timestamp, requestType, latency, statusCode, resortID
- results/throughput_over_time.csv: the throughput for every 10 seconds

Each run replaces both files.