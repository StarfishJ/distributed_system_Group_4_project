package consumer.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@ConditionalOnProperty(name = "consumer.redis.enabled", havingValue = "true")
public class ConsumerRedisConfiguration {

    @Bean
    public LettuceConnectionFactory consumerRedisConnectionFactory(
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
    public StringRedisTemplate consumerStringRedisTemplate(LettuceConnectionFactory consumerRedisConnectionFactory) {
        StringRedisTemplate t = new StringRedisTemplate();
        t.setConnectionFactory(consumerRedisConnectionFactory);
        return t;
    }

    @Bean
    public ConsumerDedupRedisService consumerDedupRedisService(
            StringRedisTemplate consumerStringRedisTemplate,
            @Value("${consumer.redis.persisted-ttl-hours:48}") long persistedTtlHours,
            @Value("${consumer.redis.broadcast-ttl-hours:48}") long broadcastTtlHours) {
        return new ConsumerDedupRedisService(consumerStringRedisTemplate, persistedTtlHours, broadcastTtlHours);
    }
}
