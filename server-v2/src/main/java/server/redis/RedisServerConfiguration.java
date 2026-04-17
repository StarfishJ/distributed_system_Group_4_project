package server.redis;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import server.ServerIdentityConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis beans for metrics cache and presence. Disabled unless {@code server.redis.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "server.redis.enabled", havingValue = "true")
public class RedisServerConfiguration {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate t = new StringRedisTemplate();
        t.setConnectionFactory(connectionFactory);
        return t;
    }

    @Bean
    public MetricsResponseRedisCache metricsResponseRedisCache(
            StringRedisTemplate redis,
            @Value("${server.metrics.cache-ttl-seconds:30}") long ttlSeconds) {
        return new MetricsResponseRedisCache(redis, Math.max(1, ttlSeconds));
    }

    @Bean
    public PresenceRegistry presenceRegistry(
            StringRedisTemplate redis,
            @Qualifier(ServerIdentityConfiguration.SERVER_INSTANCE_ID_BEAN) String serverInstanceIdentity,
            @Value("${server.presence.key-ttl-minutes:10}") long presenceTtlMinutes) {
        return new PresenceRegistry(redis, serverInstanceIdentity, Duration.ofMinutes(Math.max(1, presenceTtlMinutes)));
    }
}
