package server;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Metrics API endpoint.
 * Delegates all query logic to {@link MetricsService}.
 */
@RestController
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public Mono<Map<String, Object>> metrics(
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(required = false) String startTimeIso,
            @RequestParam(required = false) String endTimeIso,
            @RequestParam(defaultValue = "false") boolean refreshMaterializedViews) {
        return Mono.fromSupplier(() -> metricsService.getAllMetrics(
                roomId, userId, topN, startTimeIso, endTimeIso, refreshMaterializedViews));
    }
}
