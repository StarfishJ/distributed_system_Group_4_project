package consumer;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Health check endpoint for ALB.
 * ALB configuration: path=/health, interval=30s, timeout=5s
 */
@RestController
public class HealthController {

    private final ConsumerMetrics metrics;

    public HealthController(ConsumerMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of(
                "status", "UP",
                "messagesProcessed", metrics.getMessagesProcessed(),
                "messagesPublished", metrics.getMessagesPublished(),
                "publishErrors", metrics.getPublishErrors()
        ));
    }
}
