package client;

import java.net.http.HttpClient;
import java.util.concurrent.*;

import java.io.IOException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;

public class Client {
    static final String baseUrl = "http://localhost:8080/ski-servlet";
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

    public static void main(String[] args) throws InterruptedException, IOException {
        BlockingQueue<SwipeInfo> bq = new LinkedBlockingQueue<>(bufferSize);
        // HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        PoolingHttpClientConnectionManager connectionPool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnection)
                .setMaxConnPerRoute(maxConnection)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionPool)
                .build();

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
        client.close();

        // statistic
        int warmupSuccess = 0, warmupFail = 0;
        for(SwipeConsumer c : consumers) {
            warmupSuccess += c.GetSuccess();
            warmupFail += c.GetFail();
        }
        int success = warmupSuccess, fail = warmupFail;
        for(SwipeConsumer c : tasks) {
            success += c.GetSuccess();
            fail += c.GetFail();
        }
        double totalTime = (endTime - startTime) / 1000000000.0;
        double warmupTime = (WarmupCompleted - startTime) / 1000000000.0;
        double threadPoolTime = totalTime - warmupTime;

        System.out.println("-------------Total-------------");
        System.out.println("Time Cost: " + totalTime + " s");
        System.out.println("Success: " + success);
        System.out.println("Fail: " + fail);
        System.out.println("ThroughPut: " + success / totalTime + " requests per second");

        System.out.println("-------------WarmUp-------------");
        System.out.println("Threads: " + initThreadCount);
        System.out.println("PerThread: " + initPerThread);
        System.out.println("Time Cost: " + warmupTime + " s");
        System.out.println("Success: " + warmupSuccess);
        System.out.println("Fail: " + warmupFail);
        System.out.println("ThroughPut: " + warmupSuccess / warmupTime + " requests per second");

        System.out.println("-------------ThreadPool-------------");
        System.out.println("Threads: " + threadCount);
        System.out.println("Task batch size: " + batchSize);
        System.out.println("Task Sum: " + taskNum);
        System.out.println("Time Cost: " + threadPoolTime + " s");
        System.out.println("Success: " + (success - warmupSuccess));
        System.out.println("Fail: " + (fail - warmupFail));
        System.out.println("ThroughPut: " + (success - warmupSuccess) / threadPoolTime + " requests per second");
    }
}
