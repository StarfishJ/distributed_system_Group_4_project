package consumer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple circuit breaker for database writes.
 * States: CLOSED (normal) -> OPEN (fail fast) -> HALF_OPEN (probing).
 */
public class DbCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntilMs = new AtomicLong(0);

    private final int failureThreshold;
    private final long openDurationMs;

    public DbCircuitBreaker(int failureThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openDurationMs;
    }

    /** @return true if call is allowed (circuit closed or half-open) */
    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (state == State.CLOSED) return true;
        if (state == State.OPEN) {
            if (now >= openUntilMs.get()) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        // HALF_OPEN
        return true;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        state = State.CLOSED;
    }

    public void recordFailure() {
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openUntilMs.set(System.currentTimeMillis() + openDurationMs);
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            state = State.OPEN;
            openUntilMs.set(System.currentTimeMillis() + openDurationMs);
        }
    }

    public State getState() { return state; }
}
