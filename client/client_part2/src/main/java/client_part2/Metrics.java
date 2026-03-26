package client_part2;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics for Part 2 (performance analysis).
 * Optimized with LongAdder to reduce contention in hot paths.
 */
public class Metrics {

    private final LongAdder successCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder businessErrorCount = new LongAdder();
    private final LongAdder connectionCount = new LongAdder();
    private final LongAdder connectionFailureCount = new LongAdder();
    private final LongAdder reconnectCount = new LongAdder();
    
    private final LongAdder consumerLagTotalMs = new LongAdder();
    private final LongAdder consumerLagCount = new LongAdder();
    private final ConcurrentLinkedQueue<Long> consumerLagsMs = new ConcurrentLinkedQueue<>();
    
    // Note: Per-message metrics are now written directly to CSV file asynchronously
    // No need to store MessageMetric objects in memory, reducing GC pressure
    
    // Use arrays instead of Map for fixed-size room IDs (1-20) to avoid hash lookups
    private final LongAdder[] successByRoom = new LongAdder[ClientConfig.getNumRooms() + 1];
    private final Map<String, LongAdder> successByMessageType = new ConcurrentHashMap<>();

    private volatile long startTimeMs;
    private volatile long endTimeMs;

    public Metrics() {
        for (int i = 0; i < successByRoom.length; i++) {
            successByRoom[i] = new LongAdder();
        }
        successByMessageType.put("TEXT", new LongAdder());
        successByMessageType.put("JOIN", new LongAdder());
        successByMessageType.put("LEAVE", new LongAdder());
        successByMessageType.put("UNKNOWN", new LongAdder());
    }

    public void start() {
        startTimeMs = System.currentTimeMillis();
    }

    public void end() {
        endTimeMs = System.currentTimeMillis();
    }

    public void recordSuccess() {
        successCount.increment();
    }

    public void recordBusinessError() {
        businessErrorCount.increment();
    }

    private static final ThreadLocal<Integer> SAMPLING_COUNTER = ThreadLocal.withInitial(() -> 0);

    public void recordSuccessWithDetails(int roomId, String messageType, long latencyMs) {
        successCount.increment();
        
        if (roomId >= 1 && roomId < successByRoom.length) {
            successByRoom[roomId].increment();
        }
        String type = messageType != null && !messageType.isEmpty() ? messageType : "UNKNOWN";
        LongAdder typeCounter = successByMessageType.get(type);
        if (typeCounter != null) {
            typeCounter.increment();
        } else {
            successByMessageType.computeIfAbsent(type, k -> new LongAdder()).increment();
        }
    }
    
    // Asynchronous CSV writer: writes to file in background thread to avoid blocking
    private static volatile BufferedWriter csvWriter;
    private static volatile ExecutorService csvWriterExecutor;
    private static final AtomicBoolean csvWriterInitialized = new AtomicBoolean(false);
    private static final ConcurrentLinkedQueue<String> csvWriteQueue = new ConcurrentLinkedQueue<>();
    private static final int CSV_BATCH_SIZE = 1000; // Batch size for flushing

    /** Target path for {@link #initializeCsvWriter()}; default {@code results/per_message_metrics.csv}. */
    private static volatile String perMessageCsvPath = "results/per_message_metrics.csv";

    /**
     * Set CSV path before the first {@link #initializeCsvWriter()}. Ignored after initialization.
     * Parent directories are created on init.
     */
    public static void setPerMessageMetricsCsvPath(String path) {
        if (csvWriterInitialized.get()) {
            return;
        }
        if (path != null && !path.isBlank()) {
            perMessageCsvPath = path.trim();
        }
    }

    public static String getPerMessageMetricsCsvPath() {
        return perMessageCsvPath;
    }
    
    /**
     * Initialize asynchronous CSV writer (called once at start).
     */
    public static void initializeCsvWriter() {
        if (csvWriterInitialized.compareAndSet(false, true)) {
            try {
                java.io.File outFile = new java.io.File(perMessageCsvPath);
                java.io.File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    System.err.println("[Metrics] Could not create directory: " + parent);
                }
                csvWriter = new BufferedWriter(new FileWriter(outFile, false), 64 * 1024); // 64KB buffer
                csvWriter.write("timestamp,messageType,latency,statusCode,roomId\n");
                
                // Start background thread for async writes
                csvWriterExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "csv-writer");
                    t.setDaemon(true);
                    return t;
                });
                
                csvWriterExecutor.submit(() -> {
                    List<String> batch = new ArrayList<>(CSV_BATCH_SIZE);
                    boolean shutdown = false;
                    while (!shutdown) {
                        try {
                            // Collect batch from queue
                            String line;
                            while ((line = csvWriteQueue.poll()) != null && batch.size() < CSV_BATCH_SIZE) {
                                batch.add(line);
                            }
                            
                            if (!batch.isEmpty()) {
                                // Write batch
                                for (String l : batch) {
                                    csvWriter.write(l);
                                    csvWriter.write("\n");
                                }
                                csvWriter.flush();
                                batch.clear();
                            } else if (csvWriterExecutor.isShutdown()) {
                                // Executor shutdown and queue empty, exit
                                shutdown = true;
                            } else {
                                Thread.sleep(10); // Small sleep when queue is empty
                            }
                        } catch (IOException e) {
                            System.err.println("[Metrics] CSV write error: " + e.getMessage());
                            break;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            // Continue to flush remaining data before exiting
                            shutdown = true;
                        }
                    }
                    // Final flush of any remaining batch
                    try {
                        if (!batch.isEmpty() && csvWriter != null) {
                            for (String l : batch) {
                                csvWriter.write(l);
                                csvWriter.write("\n");
                            }
                            csvWriter.flush();
                        }
                    } catch (IOException e) {
                        System.err.println("[Metrics] Final CSV flush error: " + e.getMessage());
                    }
                    return null;
                });
            } catch (IOException e) {
                System.err.println("[Metrics] Failed to initialize CSV writer: " + e.getMessage());
            }
        }
    }
    
    /**
     * Shutdown CSV writer and flush remaining data.
     * Blocks until all queued data is written to file.
     */
    public static void shutdownCsvWriter() {
        if (csvWriterExecutor == null || csvWriter == null) {
            return;
        }
        try {
            int waitCount = 0;
            while (!csvWriteQueue.isEmpty() && waitCount < 600) {
                Thread.sleep(100);
                waitCount++;
            }
            csvWriterExecutor.shutdown();
            if (!csvWriterExecutor.awaitTermination(120, TimeUnit.SECONDS)) {
                csvWriterExecutor.shutdownNow();
                csvWriterExecutor.awaitTermination(30, TimeUnit.SECONDS);
            }
            csvWriter.close();
            csvWriter = null;
            System.out.println("[Metrics] CSV writer shutdown complete. Queue size: " + csvWriteQueue.size());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Metrics] Error shutting down CSV writer: " + e.getMessage());
        }
    }

    private static int percentileIndex(int n, double p) {
        if (n <= 0) return 0;
        return (int) Math.min(n - 1, Math.round((n - 1) * p));
    }

    /** OK-row latencies from {@code per_message_metrics.csv} (E2E ms); empty if missing/invalid. */
    private static List<Long> readOkLatenciesFromPerMessageCsv(String csvPath) {
        List<Long> okLatencies = new ArrayList<>(512_000);
        if (csvPath == null || csvPath.isBlank()) {
            return okLatencies;
        }
        Path path = Path.of(csvPath);
        if (!Files.isRegularFile(path)) {
            return okLatencies;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line = br.readLine();
            if (line == null || !line.contains("latency")) {
                return okLatencies;
            }
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int c0 = line.indexOf(',');
                if (c0 < 0) {
                    continue;
                }
                int c1 = line.indexOf(',', c0 + 1);
                if (c1 < 0) {
                    continue;
                }
                int c2 = line.indexOf(',', c1 + 1);
                if (c2 < 0) {
                    continue;
                }
                int c3 = line.indexOf(',', c2 + 1);
                if (c3 < 0) {
                    continue;
                }
                String status = line.substring(c2 + 1, c3).trim();
                if (!"OK".equalsIgnoreCase(status)) {
                    continue;
                }
                String latStr = line.substring(c1 + 1, c2).trim();
                try {
                    okLatencies.add(Long.parseLong(latStr));
                } catch (NumberFormatException ignored) {
                    // skip malformed
                }
            }
        } catch (IOException e) {
            System.err.println("[Metrics] Failed to read latency CSV: " + e.getMessage());
        }
        return okLatencies;
    }

    /**
     * Record detailed per-message metrics for CSV export (asynchronous write).
     * Writes to file in background thread without blocking main execution.
     * This avoids memory allocation for MessageMetric objects and reduces GC pressure.
     * @param timestamp Timestamp when acknowledgment received (milliseconds since epoch)
     * @param messageType Message type (JOIN, TEXT, LEAVE)
     * @param latency Latency in milliseconds
     * @param statusCode Status code ("OK" or "ERROR")
     * @param roomId Room ID (1-20)
     */
    public void recordMessageMetric(long timestamp, String messageType, long latency, String statusCode, int roomId) {
        if (csvWriter == null) {
            initializeCsvWriter();
            if (csvWriter == null) return; // Failed to initialize, skip recording
        }
        
        // Format CSV line as String and add to queue (non-blocking, no object allocation)
        // This is much faster than creating MessageMetric objects
        String line = timestamp + "," + 
                     (messageType != null ? messageType : "UNKNOWN") + "," +
                     latency + "," +
                     (statusCode != null ? statusCode : "UNKNOWN") + "," +
                     roomId;
        csvWriteQueue.offer(line); // Non-blocking add to queue, background thread will write it
    }

    public void recordFail() {
        failCount.increment();
    }

    public void recordConnection() {
        connectionCount.increment();
    }

    public void recordReconnect() {
        reconnectCount.increment();
    }

    public void recordConnectionFailure() {
        connectionFailureCount.increment();
    }

    public void recordConsumerLag(long lagMs) {
        if (lagMs < 0) return; // Ignore negative lag due to minor clock sync if any
        consumerLagTotalMs.add(lagMs);
        consumerLagCount.increment();
        
        int count = SAMPLING_COUNTER.get() + 1;
        SAMPLING_COUNTER.set(count);
        if (count % 500 == 0) { // Slightly higher sampling for lag
            consumerLagsMs.add(lagMs);
        }
    }

    public void recordLatencyMs(long latencyMs) {
        // no-op: percentiles come from CSV in printSummary(perMessageCsvPath) after shutdownCsvWriter()
    }

    public long getSuccessCount() { return successCount.sum(); }
    public long getFailCount() { return failCount.sum(); }
    public long getBusinessErrorCount() { return businessErrorCount.sum(); }
    public long getConnectionCount() { return connectionCount.sum(); }
    public long getConnectionFailureCount() { return connectionFailureCount.sum(); }
    public long getReconnectCount() { return reconnectCount.sum(); }

    public long getWallTimeMs() {
        if (endTimeMs == 0) return System.currentTimeMillis() - startTimeMs;
        return endTimeMs - startTimeMs;
    }

    public double getWallTimeSeconds() {
        return getWallTimeMs() / 1000.0;
    }

    public double getThroughputPerSecond() {
        long total = successCount.sum() + failCount.sum();
        if (total == 0) return 0;
        long wallMs = getWallTimeMs();
        if (wallMs <= 0) return 0;
        return total * 1000.0 / wallMs;
    }

    public void printSummary() {
        printSummary(null);
    }

    /**
     * Prints throughput summary and, after {@link #shutdownCsvWriter()}, E2E latencies from
     * {@code per_message_metrics.csv} (status=OK rows): mean, p50, p95, p99.
     */
    public void printSummary(String perMessageCsvPath) {
        long success = successCount.sum();
        long total = success + failCount.sum();
        double wallSec = getWallTimeSeconds();
        double throughput = total > 0 && wallSec > 0 ? total / wallSec : 0;

        System.out.println("------------------------------------------------------------");
        System.out.println("🚀 ASSIGNMENT 2 PERFORMANCE REPORT SUMMARY");
        System.out.println("------------------------------------------------------------");
        System.out.println(String.format("%-25s : %s", "Throughput", String.format("%.2f msg/s", throughput)));
        System.out.println(String.format("%-25s : %d", "Total Messages (Success)", success));
        System.out.println(String.format("%-25s : %.2f sec", "Total Runtime", wallSec));

        List<Long> okLatencies = readOkLatenciesFromPerMessageCsv(perMessageCsvPath);
        if (!okLatencies.isEmpty()) {
            Collections.sort(okLatencies);
            int n = okLatencies.size();
            long p50 = okLatencies.get(percentileIndex(n, 0.50));
            long p95 = okLatencies.get(percentileIndex(n, 0.95));
            long p99 = okLatencies.get(percentileIndex(n, 0.99));
            double mean = okLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
            System.out.println(String.format("%-25s : %.2f ms (n=%d OK rows)", "Mean Latency (E2E ACK)", mean, n));
            System.out.println(String.format("%-25s : %d ms", "P50 Latency", p50));
            System.out.println(String.format("%-25s : %d ms", "P95 Latency", p95));
            System.out.println(String.format("%-25s : %d ms", "P99 Latency", p99));
        } else if (perMessageCsvPath != null && !perMessageCsvPath.isBlank()) {
            System.out.println(String.format("%-25s : %s", "E2E latency (CSV)", "no OK rows or file missing — " + perMessageCsvPath));
        }

        if (consumerLagCount.sum() > 0) {
            double avgLag = consumerLagTotalMs.sum() / (double) consumerLagCount.sum();
            System.out.println(String.format("%-25s : %.2f ms", "Avg Consumer Lag", avgLag));
        }

        System.out.println(String.format("%-25s : %d", "Connection Failures", connectionFailureCount.sum()));
        System.out.println(String.format("%-25s : %.2f%%", "Success Rate", (total > 0 ? 100.0 * success / total : 0)));
        System.out.println("------------------------------------------------------------");
        
        // Compact Room Stats: only print first and last to show uniformity without spamming
        if (wallSec > 0 && successByRoom[1].sum() > 0) {
            System.out.print("Room Uniformity Check: ");
            for (int r : new int[]{1, 10, 20}) {
                System.out.print(String.format("R%d=%.1f ", r, successByRoom[r].sum() / wallSec));
            }
            System.out.println("msg/s");
        }
    }
}
