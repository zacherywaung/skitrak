package client;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.core5.http.io.HttpConnectionFactory;
import org.apache.hc.core5.pool.PoolStats;

import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

public class Client {
    static final String baseUrl = "http://localhost:8080/ski-servlet";
    static final String wsUrl = "ws://localhost:8080/ski-servlet/leaderboard/";
    static final int totalNum = 500000;
    static final int bufferSize = 8192;
    static final int resorts = 10;
    static final int seasonID = 2026;
    static final int dayID = 1;

    static final int initThreadCount = 32;
    static final int initPerThread = 1000;
    static final int threadCount = 128;              // thread pool size, to optimize
    static final int batchSize = 100;               // one task size for sending post requests
    static final int maxConnection = Math.max(initThreadCount, threadCount);
    static final long monitorInterval = 500;

    public static void main(String[] args) throws Exception {
        BlockingQueue<SwipeInfo> bq = new LinkedBlockingQueue<>(bufferSize);      // buffer
        AtomicInteger connectionCreated = new AtomicInteger(0);         // calculate connections created

        // HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        // connection factory for calculating connections
        HttpConnectionFactory<ManagedHttpClientConnection> factory = (socket) -> {
            connectionCreated.incrementAndGet();
            return ManagedHttpClientConnectionFactory.INSTANCE.createConnection(socket);
        };
        PoolingHttpClientConnectionManager connectionPool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnection)
                .setMaxConnPerRoute(maxConnection)
                .setConnectionFactory(factory)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionPool)
                .disableAutomaticRetries()
                .build();

        // start connection monitoring
        ConnectionMonitor monitor = new ConnectionMonitor(connectionPool, monitorInterval);
        Thread monitorThread = new Thread(monitor);
        monitorThread.start();

        // create 10 websocket subscriber
        WebSocketContainer wsContainer = ContainerProvider.getWebSocketContainer();
        LeaderboardSubscriber[] subscribers = new LeaderboardSubscriber[resorts];
        for(int i = 1; i <= resorts; i++) {
            subscribers[i - 1] = new LeaderboardSubscriber(i, wsUrl + i, wsContainer);
            subscribers[i - 1].connect();
        }

        // Producer thread create
        Thread producer = new Thread(new SwipeProducer(bq, totalNum, resorts, seasonID, dayID));
        producer.start();

        // Warmup: 32 threads * 1000 perThread
        long startTime = System.nanoTime();
        SwipeConsumer[] consumers = new SwipeConsumer[initThreadCount];
        Thread[] initThreads = new Thread[initThreadCount];
        for(int i = 0; i < initThreadCount; i++) {
            consumers[i] = new SwipeConsumer(bq, client, baseUrl, initPerThread);
            initThreads[i] = new Thread(consumers[i]);
            initThreads[i].start();
        }
        for(int i = 0; i < initThreadCount; i++) {
            initThreads[i].join();
        }
        long WarmupCompleted = System.nanoTime();
        int warmupConnections = connectionCreated.get();

        // Use threadPool executing task
        int afterWarmup = totalNum - initThreadCount * initPerThread;           // remaining requests to send(468000)
        int taskNum = (afterWarmup + batchSize - 1) / batchSize;                // task number
        SwipeConsumer[] tasks = new SwipeConsumer[taskNum];

        ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
        for(int i = 0; i < taskNum; i++)
        {
            if(i != taskNum - 1) {
                tasks[i] = new SwipeConsumer(bq, client, baseUrl, batchSize);
            } else{
                tasks[i] = new SwipeConsumer(bq, client, baseUrl, afterWarmup - batchSize * i);
            }
            threadPool.execute(tasks[i]);
        }
        threadPool.shutdown();
        if(!threadPool.awaitTermination(600, TimeUnit.SECONDS)) {
            System.out.println("threadPool awaitTermination timeout!!!");
        }
        long endTime = System.nanoTime();
        producer.join();
        for(int i = 0; i < resorts; i++) {
            subscribers[i].close();
        }
        monitorThread.interrupt();
        monitorThread.join();
        // PoolStats poolStats = connectionPool.getTotalStats();
        client.close();

        // statistic
        int warmupSuccess = 0, warmupFail = 0;
        int errReconnection = 0;
        int retryTimes = 0;
        List<SwipeToCsv> allRecords = new ArrayList<>(totalNum);
        for(SwipeConsumer c : consumers) {
            warmupSuccess += c.GetSuccess();
            warmupFail += c.GetFail();
            errReconnection += c.GetErrReconnect();
            retryTimes += c.GetRetryTimes();
            allRecords.addAll(c.GetRecords());
        }
        int success = warmupSuccess, fail = warmupFail;
        for(SwipeConsumer c : tasks) {
            success += c.GetSuccess();
            fail += c.GetFail();
            errReconnection += c.GetErrReconnect();
            retryTimes += c.GetRetryTimes();
            allRecords.addAll(c.GetRecords());
        }
        double totalTime = (endTime - startTime) / 1000000000.0;
        double warmupTime = (WarmupCompleted - startTime) / 1000000000.0;
        double threadPoolTime = totalTime - warmupTime;
        String filepath = "results/allswipes.csv";
        Util.writeCsv(allRecords, filepath);
        System.out.println("All swipe records in " + filepath);

        System.out.println("-------------Total-------------");
        System.out.printf("Time Cost: %.2f s%n", totalTime);
        System.out.println("Success: " + success);
        System.out.println("Total retries: " + retryTimes);
        System.out.println("Fail after retry 5 times: " + fail);
        System.out.printf("ThroughPut: %.0f requests per second%n", success / totalTime);

        System.out.println("-------------WarmUp-------------");
        System.out.println("Threads: " + initThreadCount);
        System.out.println("PerThread: " + initPerThread);
        System.out.printf("Time Cost: %.2f s%n", warmupTime);
        System.out.println("Success: " + warmupSuccess);
        System.out.println("Fail: " + warmupFail);
        System.out.printf("ThroughPut: %.0f requests per second%n", warmupSuccess / warmupTime);

        System.out.println("-------------ThreadPool-------------");
        System.out.println("Threads: " + threadCount);
        System.out.println("Task batch size: " + batchSize);
        System.out.println("Task Sum: " + taskNum);
        System.out.printf("Time Cost: %.2f s%n", threadPoolTime);
        System.out.println("Success: " + (success - warmupSuccess));
        System.out.println("Fail: " + (fail - warmupFail));
        System.out.printf("ThroughPut: %.0f requests per second%n", (success - warmupSuccess) / threadPoolTime);

        List<Double> ingestLatency = new ArrayList<>(allRecords.size());
        int[] resortCount = new int[resorts];
        long firstIngest = Long.MAX_VALUE;
        long lastIngest = Long.MIN_VALUE;
        for(SwipeToCsv e : allRecords) {
            if(e.GetStatusCode() == 201) {
                ingestLatency.add(e.GetLatency());
                resortCount[e.GetResortID() - 1]++;
            }
            firstIngest = Math.min(firstIngest, e.GetStartTime());
            lastIngest = Math.max(lastIngest, e.GetStartTime());
        }
        int intervalCnt = (int)((lastIngest - firstIngest) / 10000) + 1;
        int[] interval10 = new int[intervalCnt];
        for(SwipeToCsv e : allRecords) {
            if(e.GetStatusCode() == 201) {
                int index = (int) ((e.GetStartTime() - firstIngest));
                interval10[index]++;
            }
        }

        System.out.println("-------------Ingest Latency-------------");
        System.out.println("Ingest Latency Summary: " + Util.summary(ingestLatency));
        System.out.println("-------------Throughput for each Resort-------------");
        for(int i = 0; i < resorts; i++) {
            System.out.printf("Resort%d: %d swipes total, %.0f swipes per second%n",
                    i + 1, resortCount[i], resortCount[i] / totalTime);
        }
        System.out.println("-------------Throughput over Time(10s)-------------");
        for(int i = 0; i < intervalCnt; i++) {
            System.out.printf("%d - %d: %.0f swipes per sec%n", i * 10, i * 10 + 10, interval10[i] / 10.0);
        }
        String throughputPath = "results/throughput_over_time.cvs";
        Util.wirteThroughputToCsv(interval10, throughputPath);
        System.out.println("All throughput over time in " + throughputPath);

        System.out.println("-------------Connection Statistics-------------");
        System.out.println("Warmup Connections created: " + warmupConnections);
        System.out.println("Total Connections created: " + connectionCreated.get());
        System.out.println("Peak Connection Pool size: " + monitor.GetPeak());
        System.out.println("Reconnections: " + (connectionCreated.get() - monitor.GetPeak()));
        System.out.println("Reconnections caused by error: " + errReconnection);

        System.out.println("-------------Subscribers-------------");
        int totalMsg = 0;
        for(int i = 0; i < resorts; i++) {
            System.out.printf("Resort%d: get %d messages, connect for %.2f ms, reconnects %d times%n",
                    subscribers[i].GetResortID(), subscribers[i].GetMsgCount(),
                    subscribers[i].GetConnectTime(), subscribers[i].GetReconnectTimes());
            totalMsg += subscribers[i].GetMsgCount();
        }
        System.out.println("Total leaderboard updates delivered: " + totalMsg);

        List<Double> latenciesCombine = new ArrayList<>();
        for(int i = 0; i < resorts; i++) {
            latenciesCombine.addAll(subscribers[i].GetLatencies());
        }
        System.out.println("Push Latencies Summary: " + Util.summary(latenciesCombine));
    }
}
