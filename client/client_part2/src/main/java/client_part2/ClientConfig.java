package client_part2;

import java.io.InputStream;
import java.util.Properties;

/**
 * Read configurable parameters from client.properties, use default values for un-configured items.
 * Modify src/main/resources/client.properties to adjust parameters, no need to modify code.
 */
public final class ClientConfig {

    private static final Properties PROPS = load();

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = ClientConfig.class.getResourceAsStream("/client.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) { }
        return p;
    }

    private static int getInt(String key, int defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long getLong(String key, long defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long[] getLongArray(String key, long[] defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        String[] parts = v.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Long.parseLong(parts[i].trim()); } catch (NumberFormatException e) { return defaultValue; }
        }
        return out;
    }

    // ----- Parameters used by Main -----
    public static int getWarmupThreads() { return getInt("warmup.threads", 64); }
    public static int getWarmupMessagesPerThread() { return getInt("warmup.messagesPerThread", 500); }
    public static int getMainThreads() { return getInt("main.threads", 128); }
    public static int getMainTotalMessages() { return getInt("main.totalMessages", 500_000); }
    public static int getQueueCapacity() { return getInt("queue.capacity", 10_000); }
    public static int getConnectionStaggerEvery() { return getInt("connection.staggerEvery", 4); }
    public static int getConnectionStaggerMs() { return getInt("connection.staggerMs", 80); }

    // ----- Parameters used by Worker -----
    public static int getPipelineSize() { return getInt("worker.pipelineSize", 2000); }
    public static int getBatchSize() { return getInt("worker.batchSize", 250); }
    public static long getEchoTimeoutMs() { return getLong("worker.echoTimeoutMs", 15_000); }
    public static long getAcquireLoopTimeoutMs() { return getLong("worker.acquireLoopTimeoutMs", 30_000); }
    public static int getConnectTimeoutSec() { return getInt("worker.connectTimeoutSec", 10); }
    public static int getMaxConnectRetries() { return getInt("worker.maxConnectRetries", 10); }
    public static long[] getBackoffMs() { return getLongArray("worker.backoffMs", new long[] { 500, 1000, 2000, 4000, 8000 }); }
}
