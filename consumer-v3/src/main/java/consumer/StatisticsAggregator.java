package consumer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Assignment 3 Part 2: third thread pool — statistics aggregators.
 * Periodically snapshots {@link ConsumerMetrics} off the consumer/DB writer hot paths
 * and derives simple per-interval rates for monitoring / endurance runs.
 */
@Component
public class StatisticsAggregator {

    private static final Logger log = LoggerFactory.getLogger(StatisticsAggregator.class);

    public record Snapshot(
            long capturedAtMs,
            long messagesProcessed,
            long messagesPersisted,
            long dbWriteErrors,
            long dbWriteRetries,
            long dlqPublished,
            long backpressureNacks,
            long poisonPayloadRejects,
            /** Estimated persist rows/sec since previous snapshot; 0 on first tick. */
            double persistPerSec,
            /** Estimated broadcast completions/sec since previous snapshot. */
            double processedPerSec) {
        static Snapshot baseline(long now, ConsumerMetrics m) {
            return new Snapshot(now, m.getMessagesProcessed(), m.getMessagesPersisted(),
                    m.getDbWriteErrors(), m.getDbWriteRetries(), m.getDlqPublished(),
                    m.getBackpressureNacks(), m.getPoisonPayloadRejects(), 0, 0);
        }
    }

    private final ConsumerMetrics metrics;
    private final long intervalMs;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "statistics-aggregator");
        t.setDaemon(true);
        return t;
    });

    public StatisticsAggregator(ConsumerMetrics metrics,
            @Value("${consumer.statistics-aggregator-interval-ms:5000}") long intervalMs) {
        this.metrics = metrics;
        this.intervalMs = Math.max(1000L, intervalMs);
    }

    @PostConstruct
    public void start() {
        log.info("Statistics aggregator thread pool: interval={}ms", intervalMs);
        latest.set(Snapshot.baseline(System.currentTimeMillis(), metrics));
        executor.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** Latest snapshot (may be stale briefly during tick). */
    public Snapshot getLatestSnapshot() {
        Snapshot s = latest.get();
        return s != null ? s : Snapshot.baseline(System.currentTimeMillis(), metrics);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Snapshot prev = latest.get();
        long proc = metrics.getMessagesProcessed();
        long pers = metrics.getMessagesPersisted();
        long err = metrics.getDbWriteErrors();
        long retry = metrics.getDbWriteRetries();
        long dlq = metrics.getDlqPublished();
        long bp = metrics.getBackpressureNacks();
        long poison = metrics.getPoisonPayloadRejects();

        double dtSec = (now - prev.capturedAtMs()) / 1000.0;
        double pPerSec = 0;
        double procPerSec = 0;
        if (dtSec > 0.5) {
            pPerSec = (pers - prev.messagesPersisted()) / dtSec;
            procPerSec = (proc - prev.messagesProcessed()) / dtSec;
        }

        latest.set(new Snapshot(now, proc, pers, err, retry, dlq, bp, poison, pPerSec, procPerSec));
    }
}
