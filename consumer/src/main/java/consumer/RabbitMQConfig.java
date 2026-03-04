package consumer;

import java.net.InetAddress;
import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Consumer
 *
 * Architecture:
 * - chat.exchange is a TopicExchange (MUST match server-v2 declaration)
 * - Each consumer node creates its own exclusive, non-durable queue
 * - Node queue subscribes to ALL rooms via wildcard routing key "room.#"
 * - Dead Letter Exchange (DLX) handles messages that exceed retry limits
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "chat.exchange";
    public static final String DLX_EXCHANGE = "chat.dlx";
    public static final String DLQ_NAME = "chat.dead-letter";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * MUST be TopicExchange to match server-v2's declaration of chat.exchange.
     * RabbitMQ will throw PRECONDITION_FAILED if two apps declare the same exchange
     * with different types.
     */
    @Bean
    public TopicExchange chatExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * Dead letter exchange: receives messages rejected after failed broadcast.
     */
    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * Dead letter queue: parked here for inspection / alerting.
     */
    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ_NAME, true);
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    private String generateNodeQueueName() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String uuid = UUID.randomUUID().toString().substring(0, 8);
            return "queue.node-" + hostname + "-" + uuid;
        } catch (Exception e) {
            return "queue.node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * Per-node exclusive queue (non-durable, auto-deleted when this consumer shuts down).
     * Routing key "room.#" subscribes to all 20 rooms' messages.
     * DLX configured so rejected messages are routed to the dead letter queue.
     */
    @Bean
    public Queue nodeQueue() {
        String queueName = generateNodeQueueName();
        return QueueBuilder
                .nonDurable(queueName)
                .autoDelete()
                .deadLetterExchange(DLX_EXCHANGE)
                .build();
    }

    /**
     * Bind the node queue to chat.exchange with wildcard "room.#" so this
     * consumer instance receives messages from every room.
     */
    @Bean
    public Binding nodeQueueBinding(Queue nodeQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(nodeQueue).to(chatExchange).with("room.#");
    }
}
