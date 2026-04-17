package server;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * Metrics API endpoint.
 * Delegates all query logic to {@link MetricsService}.
 */
@RestController
public class MetricsController {

    private final MetricsService metricsService;
    private final boolean allowHttpMvRefresh;

    public MetricsController(
            MetricsService metricsService,
            @Value("${server.metrics.allow-http-mv-refresh:true}") boolean allowHttpMvRefresh) {
        this.metricsService = metricsService;
        this.allowHttpMvRefresh = allowHttpMvRefresh;
    }

    @GetMapping("/metrics")
    public Mono<Map<String, Object>> metrics(
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(required = false) String startTimeIso,
            @RequestParam(required = false) String endTimeIso,
            @RequestParam(defaultValue = "false") boolean refreshMaterializedViews) {
        if (refreshMaterializedViews && !allowHttpMvRefresh) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "refreshMaterializedViews via HTTP is disabled (server.metrics.allow-http-mv-refresh=false). "
                            + "Use scheduled MV refresh or enable the property only in trusted environments."));
        }
        return Mono.fromSupplier(() -> metricsService.getAllMetrics(
                roomId, userId, topN, startTimeIso, endTimeIso, refreshMaterializedViews));
    }
}
