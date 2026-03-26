package consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsumerErrorRecoveryConfig {

    @Bean
    public DbCircuitBreaker dbCircuitBreaker(
            @Value("${consumer.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${consumer.circuit-breaker.open-duration-ms:30000}") long openDurationMs) {
        return new DbCircuitBreaker(failureThreshold, openDurationMs);
    }
}
