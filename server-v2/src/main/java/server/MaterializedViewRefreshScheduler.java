package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional periodic {@code REFRESH MATERIALIZED VIEW} for analytics MVs after bulk loads.
 * Disabled by default; enable with {@code server.metrics.scheduled-mv-refresh-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "server.metrics.scheduled-mv-refresh-enabled", havingValue = "true")
public class MaterializedViewRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(MaterializedViewRefreshScheduler.class);

    private final MetricsService metricsService;

    public MaterializedViewRefreshScheduler(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Scheduled(cron = "${server.metrics.scheduled-mv-refresh-cron:0 0 0/6 * * ?}")
    public void refreshAnalyticsMaterializedViews() {
        try {
            metricsService.refreshMaterializedViewsForScheduledJob();
            log.info("Scheduled materialized view refresh completed");
        } catch (Exception e) {
            log.warn("Scheduled materialized view refresh failed: {}", e.getMessage());
        }
    }
}
