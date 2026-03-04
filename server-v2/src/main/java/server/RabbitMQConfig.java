package server;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Server-v2 (full-featured node):
 * - Topic exchange chat.exchange
 * - 20 durable room queues (room.1 ~ room.20) for assignment requirement
 * - Per-node private queue (non-durable, auto-delete) bound with room.# for broadcast consumption
 * - Dead Letter Exchange for failed messages
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

    @Bean
    public TopicExchange chatExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ_NAME, true);
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    /**
     * Declare all 20 room queues + bindings (assignment requirement).
     * TTL + max-length prevent memory leak since real consumption happens
     * via per-node queues, not these fixed queues.
     */
    @Bean
    public Declarables roomQueuesAndBindings(TopicExchange chatExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String queueName = "room." + i;
            Queue queue = QueueBuilder.durable(queueName)
                    .ttl(60_000)          // Messages expire after 60 seconds
                    .maxLength(10_000)     // Max 10k messages per queue
                    .overflow(QueueBuilder.Overflow.dropHead) // Drop oldest when full
                    .build();
            Binding binding = BindingBuilder.bind(queue).to(chatExchange).with(queueName);
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    /**
     * Per-node private queue: non-durable, auto-delete.
     * Each Server-v2 instance gets its own queue so every instance receives
     * a copy of every message (Pub-Sub broadcast pattern).
     */
    @Bean
    public Queue nodeQueue() {
        String queueName;
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            queueName = "queue.server-" + hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            queueName = "queue.server-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return QueueBuilder
                .nonDurable(queueName)
                .autoDelete()
                .deadLetterExchange(DLX_EXCHANGE)
                .build();
    }

    /**
     * Bind node queue to chat.exchange with wildcard "room.#" so this
     * server instance receives messages from every room for broadcasting.
     */
    @Bean
    public Binding nodeQueueBinding(Queue nodeQueue, TopicExchange chatExchange) {
        return BindingBuilder.bind(nodeQueue).to(chatExchange).with("room.#");
    }
}
