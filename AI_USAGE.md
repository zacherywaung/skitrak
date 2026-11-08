# AI Usage Reflection — Assignment 2

Tool: Claude (Anthropic). I used it as a tutor and design reviewer while building the system. I built the server and consumer step by step with the AI explaining each part; several classes started from code it suggested, which I reviewed, tested, and adapted to my own naming. The queue monitoring script, the remote test runner, and the README drafts were generated and then checked and corrected by me.

## 1. Where I Used AI

1. Architecture: understanding the swipe-to-leaderboard flow and comparing a broadcast backplane with co-location.
2. RabbitMQ setup: exchange, queues, bindings, dead-letter queue, queue arguments, and publisher confirms.
3. Server: designing the channel pool and deciding when to return 201 or 503.
4. Consumer: threading model, in-memory data structures, deduplication, and handling bad messages.
5. WebSocket: avoiding concurrent sends on one session.
6. Deployment: EC2, Docker for RabbitMQ, systemd services, AMI cloning, and ALB configuration.
7. Monitoring and debugging: the queue monitoring script, consumer lag statistics, and diagnosing a throughput stall.
8. Documentation: README drafts and the report outline.

## 2. Suggestions I Accepted

1. Channel pool backed by a BlockingQueue. I first wanted an array with round-robin. The AI explained that round-robin only decides whose turn it is, not which channel is free, so two threads could use the same channel. A queue of idle channels with borrow and return solves this.
2. Broadcast backplane through a fanout exchange, with one queue per server. It fits the separate consumer instance required by the assignment and lets me add servers without repartitioning. I also accepted coalescing leaderboard updates every 200 ms to limit fanout traffic.
3. One sender per WebSocket session that keeps only the latest leaderboard. Tomcat throws an error if a new async send starts before the previous one finishes, and this design also keeps slow clients from blocking others.
4. Disabling cookies in the load client. Otherwise the ALB stickiness cookie pins every POST to one server and the multi-server tests would not be balanced.

## 3. Suggestions I Rejected or Corrected

1. Leaderboard version numbers. The AI suggested sending a cached leaderboard to each new subscriber, with a per-resort sequence number incremented by the consumer to drop stale boards. I pointed out that the sequence restarts at 0 when the consumer restarts, so every new leaderboard would be treated as old and dropped. In the end I did not build cache-on-connect at all: in my tests all subscribers connect before the load starts and receive the next update within 200 ms, so the extra state and its race were not worth it.
2. Average queue depth in the monitoring script. The generated script averaged over every sample, including idle time before and after a test, which diluted the result. I noticed this and had it changed to average only over the active part of the run.
3. Histogram for consumer lag. The AI suggested a bucket histogram instead of storing every sample. Before accepting it, I asked whether buckets lose precision and how much memory a list of all samples would need. Lag is measured in whole milliseconds, so 1 ms buckets lose nothing below 1 s, while memory stays constant and the counters need no lock.

## 4. What the AI Got Wrong, and What I Reasoned Through Myself

1. Diagnosing the throughput stall. In my first 500K run from my laptop, throughput dropped to almost zero for about 110 seconds. The AI first blamed the server (memory, channel pool exhaustion, or RabbitMQ blocking). I suspected my own network instead. The evidence supported this: my queue monitor, which talks to RabbitMQ directly and not through the ALB, lost contact for the same 127 seconds; the ALB target response time never exceeded about 120 ms; server CPU and memory were normal; and in a repeat test even ping to 8.8.8.8 timed out. The stall started when the client opened about 480 new connections at once, which triggered the campus network's protection. I moved the client to an EC2 instance in us-west-2.
2. Channel replacement and automatic recovery. The AI's first channel pool design replaced every closed channel when it was returned. With the client library's automatic recovery, every channel looks closed while the connection is down, so this would have emptied the pool. My pool now replaces a channel only when it is closed while the connection is still open.
3. Consumer threads above 10. The assignment asks for 10 to 80 consumer threads, but per-resort ordering allows only one channel per resort queue, and there are only 10 queues. Extra threads would have no queue to read, so I tested 5, 10, and 15 threads to bracket the limit. Results were the same for all three, which showed RabbitMQ, not the consumer, was the bottleneck.

## 5. Reflection

1. The AI was most useful for explaining unfamiliar parts of RabbitMQ and AWS and for proposing starting designs. Its suggestions still had to be checked against my real data and constraints: its first guess about the stall was wrong, and two of its designs would have failed in edge cases I caught by reasoning about restarts and reconnection.

## 6. Transcripts

1. Exported conversation: transcripts folder in this repository. My Assignment 1 share link was not viewable outside my school account, so this time the transcript is included directly.