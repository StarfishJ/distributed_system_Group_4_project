package consumer;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Consumer health metrics tracked with atomic counters.
 *
 * Exposed via /health endpoint and satisfies the assignment requirement:
 * "Track: messages per second (in/out), error rates"
 *
 * Thread-safe: AtomicLong is lock-free for concurrent increment.
 */
@Component
public class ConsumerMetrics {

    // messages successfully broadcast from queue to WebSocket clients
    private final AtomicLong messagesProcessed = new AtomicLong(0);

    // messages successfully published to RabbitMQ (by ConsumerWebSocketHandler)
    private final AtomicLong messagesPublished = new AtomicLong(0);

    // publish failures (circuit open, network error, etc.)
    private final AtomicLong publishErrors = new AtomicLong(0);

    public void incrementProcessed() { messagesProcessed.incrementAndGet(); }
    public void incrementPublished()  { messagesPublished.incrementAndGet(); }
    public void incrementPublishError() { publishErrors.incrementAndGet(); }

    public long getMessagesProcessed() { return messagesProcessed.get(); }
    public long getMessagesPublished()  { return messagesPublished.get(); }
    public long getPublishErrors()      { return publishErrors.get(); }
}
