package consumer;

import java.util.Map;
import java.util.HashMap;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean; 
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.File;

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
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        double systemCpuLoad = osBean.getCpuLoad() * 100;
        long totalMemory = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMemory = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        long usedMemory = totalMemory - freeMemory;

        File root = new File("/");
        long diskTotal = root.getTotalSpace() / 1024 / 1024 / 1024;
        long diskFree = root.getFreeSpace() / 1024 / 1024 / 1024;

        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("cpuUsage", String.format("%.2f%%", systemCpuLoad));
        response.put("memoryUsedMb", usedMemory);
        response.put("memoryTotalMb", totalMemory);
        response.put("diskUsedGb", diskTotal - diskFree);
        response.put("diskTotalGb", diskTotal);
        response.put("messagesProcessed", metrics.getMessagesProcessed());
        response.put("messagesPublished", metrics.getMessagesPublished());
        response.put("publishErrors", metrics.getPublishErrors());

        return Mono.just(response);
    }
}
