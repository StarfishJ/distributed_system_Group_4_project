package server;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Server health metrics tracked with atomic counters.
 * Satisfies the assignment requirement: "Track: messages per second (in/out), error rates"
 */
@Component
public class ServerMetrics {

    // messages received from WebSocket clients
    private final AtomicLong messagesReceived = new AtomicLong(0);

    // messages successfully published to RabbitMQ
    private final AtomicLong messagesPublished = new AtomicLong(0);

    // publish failures (circuit open, network error, etc.)
    private final AtomicLong publishErrors = new AtomicLong(0);
    
    // total connected sessions
    private final AtomicLong activeSessions = new AtomicLong(0);

    public void incrementReceived() { messagesReceived.incrementAndGet(); }
    public void incrementPublished()  { messagesPublished.incrementAndGet(); }
    public void incrementPublishError() { publishErrors.incrementAndGet(); }
    
    public void sessionOpened() { activeSessions.incrementAndGet(); }
    public void sessionClosed() { activeSessions.decrementAndGet(); }

    public long getMessagesReceived() { return messagesReceived.get(); }
    public long getMessagesPublished()  { return messagesPublished.get(); }
    public long getPublishErrors()      { return publishErrors.get(); }
    public long getActiveSessions()     { return activeSessions.get(); }
}
