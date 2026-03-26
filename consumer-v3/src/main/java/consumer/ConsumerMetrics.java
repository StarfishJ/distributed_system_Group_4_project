package consumer;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Consumer health metrics for Assignment 3.
 * Extends Assignment 2 metrics with database write stats.
 */
@Component
public class ConsumerMetrics {

    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesPublished = new AtomicLong(0);
    private final AtomicLong publishErrors = new AtomicLong(0);

    private final AtomicLong messagesPersisted = new AtomicLong(0);
    private final AtomicLong dbWriteErrors = new AtomicLong(0);
    private final AtomicLong dbWriteRetries = new AtomicLong(0);
    private final AtomicLong dlqPublished = new AtomicLong(0);
    private final AtomicLong backpressureNacks = new AtomicLong(0);
    private final AtomicLong poisonPayloadRejects = new AtomicLong(0);

    public void incrementProcessed() { messagesProcessed.incrementAndGet(); }
    public void incrementPublished() { messagesPublished.incrementAndGet(); }
    public void incrementPublishError() { publishErrors.incrementAndGet(); }

    public void incrementPersisted(long n) { messagesPersisted.addAndGet(n); }
    public void incrementDbWriteError() { dbWriteErrors.incrementAndGet(); }
    public void incrementDbWriteRetry() { dbWriteRetries.incrementAndGet(); }
    public void incrementDlqPublished(long n) { dlqPublished.addAndGet(n); }
    public void incrementBackpressureNack() { backpressureNacks.incrementAndGet(); }
    public void incrementPoisonPayloadReject() { poisonPayloadRejects.incrementAndGet(); }

    public long getMessagesProcessed() { return messagesProcessed.get(); }
    public long getMessagesPublished() { return messagesPublished.get(); }
    public long getPublishErrors() { return publishErrors.get(); }
    public long getMessagesPersisted() { return messagesPersisted.get(); }
    public long getDbWriteErrors() { return dbWriteErrors.get(); }
    public long getDbWriteRetries() { return dbWriteRetries.get(); }
    public long getDlqPublished() { return dlqPublished.get(); }
    public long getBackpressureNacks() { return backpressureNacks.get(); }
    public long getPoisonPayloadRejects() { return poisonPayloadRejects.get(); }
}
