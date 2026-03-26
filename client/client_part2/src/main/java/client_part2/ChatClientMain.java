package client_part2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Entry point for Part 2 load-test client (with performance analysis).
 * Same architecture as Part 1; additionally reports P95/P99 latency (send → echo) for successful messages.
 *
 * Warmup: 32 workers × 1000 msgs. Main: 500,000 chat messages total (configurable via MAIN_THREADS).
 */
public class ChatClientMain {

    private static final ObjectMapper METRICS_JSON = new ObjectMapper();

    /** Assignment 3: print full /metrics JSON body (large; use for report screenshot). */
    private static boolean logFullMetricsJson = false;
    /** After load test, GET /metrics with {@code refreshMaterializedViews=true} so MVs match {@code messages}. */
    private static boolean refreshMaterializedViewsAfterRun = true;
    /** Write pretty-printed full /metrics JSON to {@code results/metrics_last.json} (default on; use {@code --no-metrics-save} to skip). */
    private static boolean metricsSaveToFile = true;
    /** Print full rows for list results in the console (large). */
    private static boolean metricsExpandConsole = false;

    /**
     * Where this run writes {@code per_message_metrics.csv}, {@code throughput_over_time.csv},
     * {@code metrics_last.json}. Default {@code results}. Use {@code --results-tag baseline500k} to get
     * {@code results/baseline500k/} so 500k vs 1M runs do not overwrite each other.
     */
    private static String runResultsDir = "results";

    /** Time-based main phase: generator runs for N minutes (wall clock); message count arg is ignored. */
    private static boolean enduranceMode = false;
    private static int enduranceMinutes = 30;
    /** Optional ~global enqueue rate (msg/s) during endurance (e.g. 0.8 × measured peak). */
    private static Double enduranceTargetMsgPerSec = null;

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

    /**
     * Wait until {@code success+fail} reaches {@code totalMessages}, or no progress for {@code stuckThresholdMs}, or {@code timeoutMs}.
     * If {@code totalMessages < 0}, target is unbounded (endurance drain): exit on stuck or timeout only.
     */
    private static long waitUntilCompletedOrStuck(Metrics metrics, long totalMessages, long timeoutMs, long stuckThresholdMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastCompleted = metrics.getSuccessCount() + metrics.getFailCount();
        long lastProgressMs = System.currentTimeMillis();
        long lastLogMs = 0;
        boolean unbounded = totalMessages < 0;
        String targetLabel = unbounded ? "∞" : String.valueOf(totalMessages);
        while ((unbounded || lastCompleted < totalMessages) && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
            long completed = metrics.getSuccessCount() + metrics.getFailCount();
            if (completed > lastCompleted) {
                lastCompleted = completed;
                lastProgressMs = System.currentTimeMillis();
            } else {
                long stuckMs = System.currentTimeMillis() - lastProgressMs;
                if (stuckMs > stuckThresholdMs) {
                    System.out.println("[Main] No progress for " + (stuckThresholdMs / 1000) + "s at " + lastCompleted + " / " + targetLabel + ", putting poison anyway.");
                    break;
                }

                long now = System.currentTimeMillis();
                if (stuckMs >= 10_000 && (lastLogMs == 0 || now - lastLogMs >= 10_000)) {
                    lastLogMs = now;
                    System.out.println("[Main] No progress for " + (stuckMs / 1000) + "s at " + lastCompleted + " / " + targetLabel + " (will put poison in " + ((stuckThresholdMs - stuckMs) / 1000) + "s if still stuck).");
                }
            }
        }
        long sentBeforePoison = metrics.getSuccessCount() + metrics.getFailCount();
        if (!unbounded && sentBeforePoison < totalMessages && System.currentTimeMillis() >= deadline) {
            System.out.println("[Main] Timeout waiting for all messages; completed: " + sentBeforePoison + " / " + totalMessages);
        } else if (unbounded && System.currentTimeMillis() >= deadline) {
            System.out.println("[Main] Timeout waiting for drain; completed: " + sentBeforePoison + " (endurance / unbounded target)");
        }
        return sentBeforePoison;
    }

    public static void main(String[] args) {
        String urlFromArgs = "http://localhost:8080";
        boolean skipWarmup = false;
        int messagesFromArgs = mainTotalMessages();
        int nonOptArgCount = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--results-tag=")) {
                String t = a.substring("--results-tag=".length()).trim();
                if (!applyResultsTag(t)) {
                    return;
                }
            } else if ("--results-tag".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--results-tag requires a value, e.g. --results-tag baseline500k");
                    return;
                }
                if (!applyResultsTag(args[++i].trim())) {
                    return;
                }
            }
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--results-tag=") || "--results-tag".equals(arg)) {
                if ("--results-tag".equals(arg)) {
                    i++;
                }
                continue;
            }
            if ("--no-warmup".equals(arg) || "-n".equals(arg)) {
                skipWarmup = true;
            } else if ("--metrics-json".equals(arg)) {
                logFullMetricsJson = true;
            } else if ("--no-refresh-mviews".equals(arg)) {
                refreshMaterializedViewsAfterRun = false;
            } else if ("--metrics-save".equals(arg)) {
                metricsSaveToFile = true;
            } else if ("--no-metrics-save".equals(arg)) {
                metricsSaveToFile = false;
            } else if ("--metrics-expand".equals(arg)) {
                metricsExpandConsole = true;
            } else if ("--endurance".equals(arg)) {
                enduranceMode = true;
            } else if (arg.startsWith("--endurance-minutes=")) {
                enduranceMode = true;
                try {
                    enduranceMinutes = Integer.parseInt(arg.substring("--endurance-minutes=".length()).trim());
                } catch (NumberFormatException e) {
                    System.err.println("Invalid --endurance-minutes value: " + arg);
                    return;
                }
            } else if ("--endurance-minutes".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("--endurance-minutes requires a value, e.g. --endurance-minutes 30");
                    return;
                }
                enduranceMode = true;
                String em = args[++i].trim();
                try {
                    enduranceMinutes = Integer.parseInt(em);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid endurance minutes: " + em);
                    return;
                }
            } else if (arg.startsWith("--endurance-target-msg-per-sec=")) {
                try {
                    enduranceTargetMsgPerSec = Double.parseDouble(arg.substring("--endurance-target-msg-per-sec=".length()).trim());
                } catch (NumberFormatException e) {
                    System.err.println("Invalid --endurance-target-msg-per-sec value: " + arg);
                    return;
                }
            } else if ("--endurance-target-msg-per-sec".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("--endurance-target-msg-per-sec requires a value");
                    return;
                }
                String rate = args[++i].trim();
                try {
                    enduranceTargetMsgPerSec = Double.parseDouble(rate);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid endurance target msg/s: " + rate);
                    return;
                }
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

        if (enduranceMode) {
            if (enduranceMinutes < 1 || enduranceMinutes > 24 * 60) {
                System.err.println("endurance minutes must be between 1 and " + (24 * 60));
                return;
            }
            if (enduranceTargetMsgPerSec != null && enduranceTargetMsgPerSec <= 0) {
                System.err.println("--endurance-target-msg-per-sec must be positive");
                return;
            }
        }

        final int finalTotalMessages = messagesFromArgs;
        final String serverUrl = urlFromArgs;
        final long enduranceDurationMs = enduranceMinutes * 60L * 1000L;

        if (enduranceMode && nonOptArgCount >= 2) {
            System.out.println("[Main] Endurance mode: ignoring message count " + finalTotalMessages + " (time-based load only).");
        }
        System.out.println("Part 2 Client (Performance Analysis) — server: " + serverUrl
                + (enduranceMode
                    ? (", endurance " + enduranceMinutes + " min" + (enduranceTargetMsgPerSec != null
                        ? (", throttle ~" + enduranceTargetMsgPerSec + " msg/s") : ""))
                    : (", messages: " + finalTotalMessages))
                + " (per-worker queues, sharded by room+user, rooms 1–20)");
        System.out.println("Result directory: " + runResultsDir + "/  (per-message CSV, throughput CSV, metrics JSON)");
        if (!skipWarmup) {
            System.out.println("Warmup: " + warmupThreads() + " workers × " + warmupMessagesPerThread() + " msgs (" + warmupTotal() + " total)");
        }
        System.out.println("Main:   " + mainThreads() + " workers, "
                + (enduranceMode ? ("endurance " + enduranceMinutes + " min (no fixed N)") : (finalTotalMessages + " msgs total")));
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

        System.out.println("[Main] Starting "
                + (enduranceMode ? ("(endurance " + enduranceMinutes + " min wall clock)") : ("(target " + finalTotalMessages + " messages)"))
                + ". Ensure server is running on " + serverUrl + ".");
        // Create one queue per worker thread
        List<BlockingQueue<ClientMessage>> mainQueues = createWorkerQueues(mainThreads(), queueCapacity());
        Metrics mainMetrics = new Metrics();
        java.io.File runDir = new java.io.File(runResultsDir);
        if (!runDir.exists() && !runDir.mkdirs()) {
            System.err.println("[Main] Could not create result directory: " + runResultsDir);
            return;
        }
        Metrics.setPerMessageMetricsCsvPath(new File(runDir, "per_message_metrics.csv").getPath());
        // Initialize async CSV writer for per-message metrics
        Metrics.initializeCsvWriter();
        Thread generatorMain = new Thread(enduranceMode
                ? new MessageGenerator(mainQueues, enduranceDurationMs, enduranceTargetMsgPerSec)
                : new MessageGenerator(mainQueues, finalTotalMessages));
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
            
            String progressTarget = enduranceMode ? ("elapsed " + elapsedSeconds + "s, no fixed N") : (sent + " / " + finalTotalMessages);
            System.out.println("[Main] progress: " + (enduranceMode ? (sent + " sent (" + progressTarget + ")") : (progressTarget + " sent"))
                    + " (ok=" + ok + ", fail=" + fail + ") — " + String.format("%.1f", cumulativeThroughput) + " msg/s (bucket: " + String.format("%.1f", bucketThroughput) + " msg/s)");
            
            // Record throughput data point for this 10-second bucket
            throughputData.add(new ThroughputDataPoint(elapsedSeconds, bucketThroughput));
        }, 10, 10, TimeUnit.SECONDS);

        try {
            long generatorJoinMs = enduranceMode ? (enduranceDurationMs + 600_000L) : 600_000L;
            generatorMain.join(generatorJoinMs);
            if (generatorMain.isAlive()) {
                System.err.println("[Main] WARNING: generator still running after join(" + generatorJoinMs + " ms). Endurance slack may be too small; drain phase may start while still enqueueing.");
            }
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
            long waitTargetMessages = enduranceMode ? -1L : finalTotalMessages;
            long drainWaitMs = enduranceMode ? (enduranceDurationMs + 7_200_000L) : 600_000L;
            // Endurance enqueues for N min then drains a deep backlog; 30s "stuck" fires too easily (ALB/DB/GC pauses).
            long drainStuckMs = enduranceMode ? 300_000L : 30_000L;
            long sentBeforePoison = waitUntilCompletedOrStuck(mainMetrics, waitTargetMessages, drainWaitMs, drainStuckMs);
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

        // Flush per-message CSV before reading it for full-sample percentiles
        Metrics.shutdownCsvWriter();
        mainMetrics.printSummary(Metrics.getPerMessageMetricsCsvPath());

        // Assignment 3: Call Metrics API after test and log results
        fetchAndLogMetricsApi(serverUrl, refreshMaterializedViewsAfterRun);

        // Export throughput data to CSV for chart generation
        exportThroughputData(throughputData);
        System.out.println("[Main] Per-message metrics CSV export completed (async write).");
    }

    /**
     * Assignment 3: GET /metrics after the run, log results on the client (screenshot for report).
     * Default: human-readable summary. Flags: {@code --metrics-json} (stdout), {@code --metrics-save} (file),
     * {@code --metrics-expand} (all list rows in console), {@code --no-refresh-mviews}.
     */
    private static void fetchAndLogMetricsApi(String serverBaseUrl, boolean refreshMaterializedViews) {
        String base = serverBaseUrl.replaceFirst("/$", "") + "/metrics";
        String metricsUrl1 = refreshMaterializedViews ? base + "?refreshMaterializedViews=true" : base;
        System.out.println();
        StringBuilder flags = new StringBuilder();
        if (logFullMetricsJson) {
            flags.append(" --metrics-json");
        }
        if (!metricsSaveToFile) {
            flags.append(" --no-metrics-save");
        }
        if (metricsExpandConsole) {
            flags.append(" --metrics-expand");
        }
        System.out.println("--- METRICS ---  " + metricsUrl1 + flags);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request1 = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl1))
                    .GET()
                    .build();
            HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
            int code = response1.statusCode();
            String body = response1.body();
            System.out.println("HTTP Status: " + code + (code >= 200 && code < 300 ? " OK" : ""));

            /*
             * Server defaults userId=1; synthetic load uses many ids. Re-query so Core 2/4 use a real user.
             * Prefer analytics "most active user" (likely multi-room); else first Core 1 row — no server change.
             */
            if (code >= 200 && code < 300) {
                String uid = pickUserIdForMetricsFollowup(body);
                String rid = pickRoomIdFromMetricsParamsOrCore1(body);
                if (uid != null) {
                    String encU = URLEncoder.encode(uid, StandardCharsets.UTF_8);
                    String encR = URLEncoder.encode(rid, StandardCharsets.UTF_8);
                    String metricsUrl2 = base + "?refreshMaterializedViews=false&roomId=" + encR + "&userId=" + encU;
                    System.out.println("[Metrics API] Follow-up (Core 2/4 user scope): " + metricsUrl2);
                    HttpRequest request2 = HttpRequest.newBuilder()
                            .uri(URI.create(metricsUrl2))
                            .GET()
                            .build();
                    HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
                    int code2 = response2.statusCode();
                    if (code2 >= 200 && code2 < 300) {
                        body = response2.body();
                        code = code2;
                        System.out.println("HTTP Status (follow-up): " + code2 + " OK");
                    } else {
                        System.err.println("[Metrics API] Follow-up failed: " + code2 + " — using first response for log/file.");
                    }
                }
            }

            if (code < 200 || code >= 300) {
                System.out.println("[Metrics API] Error body (truncated): "
                        + body.substring(0, Math.min(800, body.length())));
            } else {
                if (metricsSaveToFile) {
                    saveMetricsJsonToFile(body);
                }
                if (logFullMetricsJson) {
                    System.out.println("[Metrics] Full JSON response:");
                    System.out.println(body);
                } else {
                    logMetricsResultsSummary(body, metricsExpandConsole);
                }
            }
        } catch (Exception e) {
            System.err.println("[Metrics API] Failed to fetch: " + e.getMessage());
        }
        System.out.println("--- end METRICS ---");
        System.out.flush();
    }

    /**
     * Chooses userId for the second /metrics call so Core 2 and Core 4 match real data.
     * Prefers {@code analyticsQueries.2_mostActiveUsers[0]} (global top user → more likely multi-room for Core 4).
     */
    private static String pickUserIdForMetricsFollowup(String body) {
        try {
            JsonNode root = METRICS_JSON.readTree(body);
            JsonNode topUsers = root.path("analyticsQueries").path("2_mostActiveUsers");
            if (topUsers.isArray() && topUsers.size() > 0) {
                String u = topUsers.get(0).path("userId").asText("");
                if (!u.isBlank()) {
                    return u;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return pickUserIdFromMetricsCore1(body);
    }

    /** Fallback: first Core 1 row (that user may only have room 1 → Core 4 can show 1 row). */
    private static String pickUserIdFromMetricsCore1(String body) {
        try {
            JsonNode root = METRICS_JSON.readTree(body);
            JsonNode arr = root.path("coreQueries").path("1_roomMessages");
            if (arr.isArray() && arr.size() > 0) {
                String u = arr.get(0).path("userId").asText("");
                if (!u.isBlank()) {
                    return u;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private static String pickRoomIdFromMetricsParamsOrCore1(String body) {
        try {
            JsonNode root = METRICS_JSON.readTree(body);
            String r = root.path("params").path("roomId").asText("");
            if (!r.isBlank()) {
                return r;
            }
            JsonNode arr = root.path("coreQueries").path("1_roomMessages");
            if (arr.isArray() && arr.size() > 0) {
                String rid = arr.get(0).path("roomId").asText("");
                if (!rid.isBlank()) {
                    return rid;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return "1";
    }

    private static void saveMetricsJsonToFile(String body) {
        try {
            java.io.File resultsDir = new java.io.File(runResultsDir);
            if (!resultsDir.exists() && !resultsDir.mkdirs()) {
                System.err.println("[Metrics] Could not create result directory: " + runResultsDir);
                return;
            }
            JsonNode root = METRICS_JSON.readTree(body);
            File out = new File(resultsDir, "metrics_last.json");
            METRICS_JSON.writerWithDefaultPrettyPrinter().writeValue(out, root);
            System.out.println("[Metrics] Wrote " + out.getPath());
        } catch (Exception e) {
            System.err.println("[Metrics] Save failed: " + e.getMessage());
        }
    }

    /**
     * Parses /metrics JSON. Assignment text distinguishes:
     * Analytics = optional aggregated stats (e.g. per-minute charting, top-N, participation), not Core requirements.
     * Core = the four lookup queries (includes ordered message lists where the spec requires them).
     */
    private static void logMetricsResultsSummary(String body, boolean expandLists) {
        try {
            JsonNode root = METRICS_JSON.readTree(body);
            if (root.has("error")) {
                System.out.println("[Metrics] error: " + root.get("error").asText());
            }
            if (root.has("message")) {
                System.out.println("[Metrics] message: " + root.get("message").asText());
            }

            System.out.println();
            System.out.println("---------- Analytics (aggregated) ----------");
            printAnalyticsHumanReadable(root.get("analyticsQueries"), expandLists);

            System.out.println();
            System.out.println("---------- Core queries ----------");
            printCoreHumanReadable(root.get("coreQueries"), root.get("params"), expandLists);
            System.out.flush();
        } catch (Exception e) {
            System.out.println("[Metrics] Summary parse failed: " + e.getMessage());
            System.out.println("[Metrics] Raw (first 600 chars): " + body.substring(0, Math.min(600, body.length())));
            System.out.flush();
        }
    }

    private static void printAnalyticsHumanReadable(JsonNode analytics, boolean expandLists) {
        if (analytics == null || !analytics.isObject()) {
            System.out.println("(no analytics data)");
            return;
        }

        JsonNode perMin = analytics.get("1_messagesPerMinute");
        if (perMin != null && perMin.isArray()) {
            long sum = 0;
            for (JsonNode row : perMin) {
                sum += row.path("count").asLong(0);
            }
            System.out.println("1) Analytics — message volume by 1-minute intervals (for charts/trends; not a Core-query requirement): "
                    + perMin.size() + " intervals, sum of counts=" + sum + ".");
            int show = expandLists ? perMin.size() : Math.min(3, perMin.size());
            if (show > 0 && !expandLists) {
                System.out.println("   Collapsed: first " + show + " intervals (full: --metrics-expand or results/metrics_last.json):");
            } else if (expandLists && show > 0) {
                System.out.println("   All intervals:");
            }
            for (int i = 0; i < show; i++) {
                JsonNode row = perMin.get(i);
                System.out.println("   - " + row.path("minute").asText() + " -> " + row.path("count").asLong() + " messages");
            }
        }

        JsonNode topUsers = analytics.get("2_mostActiveUsers");
        if (topUsers != null && topUsers.isArray()) {
            System.out.println("2) Most active users (top " + topUsers.size() + " rows, by message count):");
            for (int i = 0; i < topUsers.size(); i++) {
                JsonNode row = topUsers.get(i);
                System.out.println("   #" + (i + 1) + " userId=" + row.path("userId").asText() + " -> "
                        + row.path("messageCount").asLong() + " messages");
            }
        }

        JsonNode topRooms = analytics.get("3_mostActiveRooms");
        if (topRooms != null && topRooms.isArray()) {
            System.out.println("3) Most active rooms (top " + topRooms.size() + " rows, by message count):");
            for (int i = 0; i < topRooms.size(); i++) {
                JsonNode row = topRooms.get(i);
                System.out.println("   #" + (i + 1) + " roomId=" + row.path("roomId").asText() + " -> "
                        + row.path("messageCount").asLong() + " messages");
            }
        }

        JsonNode part = analytics.get("4_userParticipationPatterns");
        if (part != null && part.isArray()) {
            System.out.println("4) Participation per room: " + part.size() + " row(s).");
            int cap = expandLists ? part.size() : Math.min(5, part.size());
            for (int i = 0; i < cap; i++) {
                JsonNode row = part.get(i);
                System.out.println("   - room " + row.path("roomId").asText() + ": "
                        + row.path("uniqueUsers").asLong() + " distinct user(s), "
                        + row.path("totalMessages").asLong() + " messages total");
            }
            if (!expandLists && part.size() > cap) {
                System.out.println("   ... " + (part.size() - cap) + " more (use --metrics-save or --metrics-expand)");
            }
        }
    }

    private static void printCoreHumanReadable(JsonNode core, JsonNode params, boolean expandLists) {
        if (core == null || !core.isObject()) {
            System.out.println("(no core query data)");
            return;
        }
        String paramUserId = params != null && params.isObject() ? params.path("userId").asText("") : "";
        String paramRoomId = params != null && params.isObject() ? params.path("roomId").asText("") : "";

        JsonNode roomMsgs = core.get("1_roomMessages");
        if (roomMsgs != null && roomMsgs.isArray()) {
            int n = roomMsgs.size();
            System.out.println("1) Room messages (time window, roomId=" + (paramRoomId.isEmpty() ? "?" : paramRoomId)
                    + "): " + n + " row(s), cap 1000 — list result.");
            if (n > 0) {
                JsonNode first = roomMsgs.get(0);
                JsonNode last = roomMsgs.get(n - 1);
                System.out.println("   Collapsed: earliest … latest");
                System.out.println("   Earliest: " + first.path("timestamp").asText() + ", userId="
                        + first.path("userId").asText() + ", preview=\"" + truncateForConsole(first.path("message").asText(), 40) + "\"");
                System.out.println("   Latest:   " + last.path("timestamp").asText() + ", userId="
                        + last.path("userId").asText() + ", preview=\"" + truncateForConsole(last.path("message").asText(), 40) + "\"");
                if (expandLists) {
                    System.out.println("   All rows:");
                    for (int i = 0; i < n; i++) {
                        JsonNode row = roomMsgs.get(i);
                        System.out.println("   [" + i + "] " + row.path("timestamp").asText() + " room=" + row.path("roomId").asText()
                                + " user=" + row.path("userId").asText() + " " + truncateForConsole(row.path("message").asText(), 80));
                    }
                }
            }
        }

        JsonNode hist = core.get("2_userHistory");
        if (hist != null && hist.isArray()) {
            int n = hist.size();
            System.out.println("2) User history (userId=" + (paramUserId.isEmpty() ? "?" : paramUserId)
                    + ", same time window, all rooms): " + n + " row(s), cap 500 — list result.");
            if (n > 0) {
                final int headCollapsed = 10;
                if (expandLists) {
                    System.out.println("   All rows:");
                    for (int i = 0; i < n; i++) {
                        JsonNode row = hist.get(i);
                        printUserHistoryLine(i, row);
                    }
                } else {
                    int show = Math.min(headCollapsed, n);
                    System.out.println("   First " + show + " row(s) (oldest → newest in API order):");
                    for (int i = 0; i < show; i++) {
                        printUserHistoryLine(i, hist.get(i));
                    }
                    if (n > show) {
                        System.out.println("   ... +" + (n - show) + " more (see results/metrics_last.json or --metrics-expand)");
                    }
                    JsonNode last = hist.get(n - 1);
                    if (n > 1) {
                        System.out.println("   Latest row: " + last.path("timestamp").asText() + " room="
                                + last.path("roomId").asText() + " \"" + truncateForConsole(last.path("message").asText(), 50) + "\"");
                    }
                }
            }
        }

        JsonNode active = core.get("3_activeUserCount");
        JsonNode activeSample = core.get("3_activeUserIdsSample");
        if (active != null && active.isNumber()) {
            long distinct = active.asLong();
            System.out.println("3) Active users in window — required output: uniqueUserCount = " + distinct
                    + " (COUNT DISTINCT user_id over params.timeRange).");
            if (activeSample != null && activeSample.isArray() && activeSample.size() > 0) {
                int sn = activeSample.size();
                if (expandLists) {
                    System.out.println("   Preview (lexicographic string order on user_id; not numeric):");
                    for (int i = 0; i < sn; i++) {
                        System.out.println("      user_id=\"" + activeSample.get(i).asText() + "\"");
                    }
                } else {
                    System.out.println("   Id list omitted in console (" + sn + " ids in JSON, lexicographic text order). "
                            + "Open results/metrics_last.json → coreQueries.3_activeUserIdsSample or use --metrics-expand.");
                }
            } else if (activeSample == null || !activeSample.isArray()) {
                System.out.println("   (No id sample field; count above is still the spec output.)");
            }
        }

        JsonNode rooms = core.get("4_userRooms");
        if (rooms != null && rooms.isArray()) {
            int n = rooms.size();
            final int maxConsoleRows = 20;
            String uidLabel = paramUserId.isEmpty() ? "(unknown — see JSON params.userId)" : paramUserId;
            System.out.println("4) Rooms the user has participated in (Core 4)");
            System.out.println("   Sample userId (input to this query): " + uidLabel);
            System.out.println("   Participated rooms (output): " + n + " row(s); each row is roomId + lastActivity"
                    + " (most recent message in that room; newest room first).");
            if (n == 0) {
                System.out.println("   (No rows — refresh MVs on first /metrics call, or use a userId that has messages.)");
            } else {
                int cap = expandLists ? n : Math.min(maxConsoleRows, n);
                if (!expandLists && n > maxConsoleRows) {
                    System.out.println("   Listing first " + cap + " of " + n + " (--metrics-expand or metrics_last.json for all):");
                }
                for (int i = 0; i < cap; i++) {
                    JsonNode row = rooms.get(i);
                    System.out.println("   - roomId=" + row.path("roomId").asText() + ", lastActivity="
                            + row.path("lastActivity").asText());
                }
                if (!expandLists && n > maxConsoleRows) {
                    System.out.println("   ... " + (n - cap) + " more in results/metrics_last.json");
                }
            }
        }
    }

    private static void printUserHistoryLine(int index, JsonNode row) {
        System.out.println("   [" + index + "] " + row.path("timestamp").asText() + " room=" + row.path("roomId").asText()
                + " msg=\"" + truncateForConsole(row.path("message").asText(), 70) + "\"");
    }

    private static String truncateForConsole(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
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
            java.io.File resultsDir = new java.io.File(runResultsDir);
            if (!resultsDir.exists() && !resultsDir.mkdirs()) {
                System.err.println("[Main] Could not create result directory: " + runResultsDir);
                return;
            }
            String csvFile = new File(resultsDir, "throughput_over_time.csv").getPath();
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

    private static boolean applyResultsTag(String tag) {
        if (tag == null || tag.isBlank()) {
            System.err.println("Empty --results-tag");
            return false;
        }
        if (!tag.matches("[a-zA-Z0-9._-]+")) {
            System.err.println("Invalid --results-tag \"" + tag + "\" (use letters, digits, . _ - only)");
            return false;
        }
        runResultsDir = new File("results", tag).getPath();
        return true;
    }
}
