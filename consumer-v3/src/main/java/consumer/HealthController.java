package consumer;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sun.management.OperatingSystemMXBean;

import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Mono;

@RestController
public class HealthController {

    private final ConsumerMetrics metrics;
    private final StatisticsAggregator statisticsAggregator;
    private OperatingSystemMXBean osBean;

    public HealthController(ConsumerMetrics metrics, StatisticsAggregator statisticsAggregator) {
        this.metrics = metrics;
        this.statisticsAggregator = statisticsAggregator;
    }

    @PostConstruct
    public void init() {
        try {
            osBean = java.lang.management.ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        } catch (Exception e) {
            osBean = null;
        }
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("messagesProcessed", metrics.getMessagesProcessed());
        response.put("messagesPersisted", metrics.getMessagesPersisted());
        response.put("publishErrors", metrics.getPublishErrors());
        response.put("dbWriteErrors", metrics.getDbWriteErrors());
        response.put("dbWriteRetries", metrics.getDbWriteRetries());
        response.put("dlqPublished", metrics.getDlqPublished());
        response.put("backpressureNacks", metrics.getBackpressureNacks());
        response.put("poisonPayloadRejects", metrics.getPoisonPayloadRejects());
        response.put("dlqReplayRowsSucceeded", metrics.getDlqReplaySuccess());
        response.put("dlqReplayRowsExhausted", metrics.getDlqReplayExhausted());
        response.put("dlqReplayPoisonDiscards", metrics.getDlqReplayPoisonDiscard());
        response.put("dlqAuditEvidenceRows", metrics.getDlqAuditEvidence());

        StatisticsAggregator.Snapshot snap = statisticsAggregator.getLatestSnapshot();
        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("capturedAtMs", snap.capturedAtMs());
        agg.put("persistPerSec", String.format("%.2f", snap.persistPerSec()));
        agg.put("broadcastProcessedPerSec", String.format("%.2f", snap.processedPerSec()));
        response.put("statisticsAggregator", agg);

        if (osBean != null) {
            response.put("cpuUsage", String.format("%.2f%%", osBean.getCpuLoad() * 100));
        }
        long totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        response.put("memoryUsedMb", totalMemory - freeMemory);
        response.put("memoryTotalMb", totalMemory);

        try {
            File root = new File("/");
            response.put("diskTotalGb", root.getTotalSpace() / 1024 / 1024 / 1024);
        } catch (Exception e) {
            response.put("diskTotalGb", "N/A");
        }

        return Mono.just(response);
    }
}
