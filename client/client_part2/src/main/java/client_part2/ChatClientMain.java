package client_part2;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Entry point for Part 2 load-test client (with performance analysis).
 * Same architecture as Part 1; additionally reports P95/P99 latency (send → echo) for successful messages.
 *
 * Warmup: 32 workers × 1000 msgs. Main: 500,000 chat messages total (configurable via MAIN_THREADS).
 */
public class ChatClientMain {

    private static int warmupThreads() { return ClientConfig.getWarmupThreads(); }
    private static int warmupMessagesPerThread() { return ClientConfig.getWarmupMessagesPerThread(); }
    private static int warmupTotal() { return warmupThreads() * warmupMessagesPerThread(); }
    private static int mainThreads() { return ClientConfig.getMainThreads(); }
    private static int mainTotalMessages() { return ClientConfig.getMainTotalMessages(); }
    private static int queueCapacity() { return ClientConfig.getQueueCapacity(); }
    private static int connectionStaggerEvery() { return ClientConfig.getConnectionStaggerEvery(); }
    private static int connectionStaggerMs() { return ClientConfig.getConnectionStaggerMs(); }

    private static List<BlockingQueue<ClientMessage>> createWorkerQueues(int count, int capacity) {
        List<BlockingQueue<ClientMessage>> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new LinkedBlockingDeque<>(capacity));
        }
        return list;
    }

    private static void submitWorkersStaggered(ExecutorService pool, int totalWorkers, IntFunction<Worker> workerForIndex) throws InterruptedException {
        int staggerEvery = connectionStaggerEvery();
        int numBatches = (totalWorkers + staggerEvery - 1) / staggerEvery;
        CountDownLatch lastBatchDone = new CountDownLatch(1);
        ScheduledExecutorService stagger = Executors.newSingleThreadScheduledExecutor();
        for (int batch = 0; batch < numBatches; batch++) {
            final int start = batch * staggerEvery;
            final boolean isLast = (batch == numBatches - 1);
            stagger.schedule(() -> {
                int end = Math.min(start + staggerEvery, totalWorkers);
                for (int i = start; i < end; i++) {
                    pool.submit(workerForIndex.apply(i));
                }
                if (isLast) lastBatchDone.countDown();
            }, (long) batch * connectionStaggerMs(), TimeUnit.MILLISECONDS);
        }
        lastBatchDone.await();
        stagger.shutdown();
        try {
            if (!stagger.awaitTermination(1, TimeUnit.MINUTES)) {
                stagger.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stagger.shutdownNow();
            throw e;
        }
    }

    private static long waitUntilCompletedOrStuck(Metrics metrics, long totalMessages, long timeoutMs, long stuckThresholdMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastCompleted = metrics.getSuccessCount() + metrics.getFailCount();
        long lastProgressMs = System.currentTimeMillis();
        long lastLogMs = 0;
        while (lastCompleted < totalMessages && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
            long completed = metrics.getSuccessCount() + metrics.getFailCount();
            if (completed > lastCompleted) {
                lastCompleted = completed;
                lastProgressMs = System.currentTimeMillis();
            } else {
                long stuckMs = System.currentTimeMillis() - lastProgressMs;
                if (stuckMs > stuckThresholdMs) {
                    System.out.println("[Main] No progress for " + (stuckThresholdMs / 1000) + "s at " + lastCompleted + " / " + totalMessages + ", putting poison anyway.");
                    break;
                }
                
                long now = System.currentTimeMillis();
                if (stuckMs >= 10_000 && (lastLogMs == 0 || now - lastLogMs >= 10_000)) {
                    lastLogMs = now;
                    System.out.println("[Main] No progress for " + (stuckMs / 1000) + "s at " + lastCompleted + " / " + totalMessages + " (will put poison in " + ((stuckThresholdMs - stuckMs) / 1000) + "s if still stuck).");
                }
            }
        }
        long sentBeforePoison = metrics.getSuccessCount() + metrics.getFailCount();
        if (sentBeforePoison < totalMessages && System.currentTimeMillis() >= deadline) {
            System.out.println("[Main] Timeout waiting for all messages; completed: " + sentBeforePoison + " / " + totalMessages);
        }
        return sentBeforePoison;
    }

    public static void main(String[] args) {
        String urlFromArgs = "http://localhost:8080";
        boolean skipWarmup = false;
        int messagesFromArgs = mainTotalMessages();
        int nonOptArgCount = 0;
        
        for (String arg : args) {
            if ("--no-warmup".equals(arg) || "-n".equals(arg)) {
                skipWarmup = true;
            } else if (!arg.startsWith("-")) {
                if (nonOptArgCount == 0) {
                    urlFromArgs = arg;
                    nonOptArgCount++;
                } else if (nonOptArgCount == 1) {
                    try {
                        messagesFromArgs = Integer.parseInt(arg);
                        nonOptArgCount++;
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid message count: " + arg);
                    }
                }
            }
        }
        
        final int finalTotalMessages = messagesFromArgs;   
        final String serverUrl = urlFromArgs;
        System.out.println("Part 2 Client (Performance Analysis) — server: " + serverUrl + ", messages: " + finalTotalMessages + " (per-worker queues, sharded by room+user, rooms 1–20)");
        if (!skipWarmup) {
            System.out.println("Warmup: " + warmupThreads() + " workers × " + warmupMessagesPerThread() + " msgs (" + warmupTotal() + " total)");
        }
        System.out.println("Main:   " + mainThreads() + " workers, " + finalTotalMessages + " msgs total");
        System.out.println("---");

        if (!skipWarmup) {
            System.out.println("[Warmup] Starting...");
            // Create one queue per worker thread (32 queues)
            List<BlockingQueue<ClientMessage>> warmupQueues = createWorkerQueues(warmupThreads(), queueCapacity());
            int[] messagesPerWorker = new int[warmupThreads()];
            for (int i = 0; i < warmupThreads(); i++) messagesPerWorker[i] = warmupMessagesPerThread();
            Metrics warmupMetrics = new Metrics();
            Thread generatorWarmup = new Thread(new MessageGenerator(warmupQueues, messagesPerWorker));
            generatorWarmup.start();

            ExecutorService warmupPool = Executors.newFixedThreadPool(warmupThreads());
            warmupMetrics.start();
            try {
                submitWorkersStaggered(warmupPool, warmupThreads(),
                        i -> new Worker(warmupQueues.get(i), serverUrl, warmupMetrics, i, warmupMessagesPerThread()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Warmup interrupted");
                return;
            }
            warmupPool.shutdown();
            try {
                generatorWarmup.join(60_000);
                warmupPool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Warmup interrupted");
                return;
            }
            warmupMetrics.end();
            System.out.println("[Warmup] Done in " + warmupMetrics.getWallTimeMs() + " ms (success=" + warmupMetrics.getSuccessCount() + ", fail=" + warmupMetrics.getFailCount() + ", " + String.format("%.1f", warmupMetrics.getThroughputPerSecond()) + " msg/s)");
            System.out.println("---");
        }

        System.out.println("[Main] Starting (target " + finalTotalMessages + " messages). Ensure server is running on " + serverUrl + ".");
        // Create one queue per worker thread
        List<BlockingQueue<ClientMessage>> mainQueues = createWorkerQueues(mainThreads(), queueCapacity());
        Metrics mainMetrics = new Metrics();
        // Initialize async CSV writer for per-message metrics
        Metrics.initializeCsvWriter();
        Thread generatorMain = new Thread(new MessageGenerator(mainQueues, finalTotalMessages));
        generatorMain.start();

        ExecutorService mainPool = Executors.newFixedThreadPool(mainThreads());
        mainMetrics.start();
        try {
            submitWorkersStaggered(mainPool, mainThreads(),
                    i -> new Worker(mainQueues.get(i), serverUrl, mainMetrics, i));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main phase interrupted");
            return;
        }
        mainPool.shutdown();

        // Store throughput data points for chart generation (10-second buckets)
        List<ThroughputDataPoint> throughputData = new ArrayList<>();
        long mainStartTimeMs = System.currentTimeMillis();
        long[] lastMessageCount = new long[1]; // Track previous message count for bucket calculation
        
        ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress");
            t.setDaemon(true);
            return t;
        });
        progressScheduler.scheduleAtFixedRate(() -> {
            if (mainPool.isTerminated()) return;
            long ok = mainMetrics.getSuccessCount();
            long fail = mainMetrics.getFailCount();
            long sent = ok + fail;
            double cumulativeThroughput = mainMetrics.getThroughputPerSecond();
            long elapsedSeconds = (System.currentTimeMillis() - mainStartTimeMs) / 1000;
            
            // Calculate throughput for this 10-second bucket (incremental)
            long messagesInBucket = sent - lastMessageCount[0];
            double bucketThroughput = messagesInBucket / 10.0; // messages per second in this bucket
            lastMessageCount[0] = sent;
            
            System.out.println("[Main] progress: " + sent + " / " + finalTotalMessages + " sent (ok=" + ok + ", fail=" + fail + ") — " + String.format("%.1f", cumulativeThroughput) + " msg/s (bucket: " + String.format("%.1f", bucketThroughput) + " msg/s)");
            
            // Record throughput data point for this 10-second bucket
            throughputData.add(new ThroughputDataPoint(elapsedSeconds, bucketThroughput));
        }, 10, 10, TimeUnit.SECONDS);

        try {
            generatorMain.join(600_000); // 10 mins for generator if 2M
            int queueTotal = 0;
            List<Integer> nonExhaustedRooms = new ArrayList<>();
            for (int i = 0; i < mainQueues.size(); i++) {
                int size = mainQueues.get(i).size();
                queueTotal += size;
                if (size > 0) nonExhaustedRooms.add((i % ClientConfig.getNumRooms()) + 1);
            }
            System.out.println("[Main] Generator joined. Queue total size: " + queueTotal + " (remaining in queues). Rooms still pending: " + nonExhaustedRooms);
            long deadline = System.currentTimeMillis() + 30_000;
            while (mainMetrics.getSuccessCount() + mainMetrics.getFailCount() < 100 && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(500);
            }
            long sentBeforePoison = waitUntilCompletedOrStuck(mainMetrics, finalTotalMessages, 600_000, 30_000);
            System.out.println("[Main] Putting poison (sent so far: " + sentBeforePoison + ")...");
            for (int i = 0; i < mainThreads(); i++) {
                mainQueues.get(i).put(ClientMessage.POISON);
            }
            System.out.println("[Main] Poison pills added. Waiting for workers to finish...");
            mainPool.awaitTermination(300, TimeUnit.SECONDS);
            progressScheduler.shutdown();
        } catch (InterruptedException e) {
            progressScheduler.shutdown();
            Thread.currentThread().interrupt();
            System.err.println("Main phase interrupted");
            return;
        }
        mainMetrics.end();
        System.out.println("[Main] Finished.");
        System.out.println();
        mainMetrics.printSummary();
        
        // Export throughput data to CSV for chart generation
        exportThroughputData(throughputData);
        
        // Shutdown async CSV writer and wait for all data to be written
        Metrics.shutdownCsvWriter();
        System.out.println("[Main] Per-message metrics CSV export completed (async write).");
    }
    
    private static class ThroughputDataPoint {
        final long timeSeconds;
        final double throughputMsgPerSec;
        
        ThroughputDataPoint(long timeSeconds, double throughputMsgPerSec) {
            this.timeSeconds = timeSeconds;
            this.throughputMsgPerSec = throughputMsgPerSec;
        }
    }
    
    private static void exportThroughputData(List<ThroughputDataPoint> data) {
        if (data.isEmpty()) {
            System.out.println("[Main] No throughput data to export.");
            return;
        }
        try {
            // Create results directory if it doesn't exist
            java.io.File resultsDir = new java.io.File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }
            
            String csvFile = "results/throughput_over_time.csv";
            FileWriter writer = new FileWriter(csvFile);
            writer.write("Time (seconds),Throughput (msg/s)\n");
            for (ThroughputDataPoint point : data) {
                writer.write(point.timeSeconds + "," + String.format("%.2f", point.throughputMsgPerSec) + "\n");
            }
            writer.close();
            System.out.println("[Main] Throughput data exported to: " + csvFile + " (" + data.size() + " data points)");
        } catch (IOException e) {
            System.err.println("[Main] Failed to export throughput data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
