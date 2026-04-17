package server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Optional second JDBC URL for {@link MetricsService}: all {@code SELECT} paths use the read replica;
 * {@code REFRESH MATERIALIZED VIEW} stays on the primary {@link JdbcTemplate} (replicas cannot refresh MVs).
 */
@Configuration
@ConditionalOnProperty(name = "server.metrics.read-replica.enabled", havingValue = "true")
public class MetricsReadReplicaConfiguration {

    @Bean(name = "metricsReadJdbcTemplate")
    public JdbcTemplate metricsReadJdbcTemplate(
            @Value("${server.metrics.read-replica.jdbc-url:}") String jdbcUrl,
            @Value("${server.metrics.read-replica.username:}") String readUsername,
            @Value("${server.metrics.read-replica.password:}") String readPassword,
            @Value("${spring.datasource.username:chat}") String defaultUsername,
            @Value("${spring.datasource.password:chat}") String defaultPassword,
            @Value("${server.metrics.read-replica.maximum-pool-size:5}") int maxPoolSize) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                    "server.metrics.read-replica.enabled=true but server.metrics.read-replica.jdbc-url is empty");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl.trim());
        ds.setUsername(readUsername != null && !readUsername.isBlank() ? readUsername.trim() : defaultUsername);
        ds.setPassword(readPassword != null && !readPassword.isBlank() ? readPassword : defaultPassword);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setPoolName("metrics-read-replica");
        ds.setMaximumPoolSize(Math.max(1, maxPoolSize));
        return new JdbcTemplate(ds);
    }
}
