package consumer;

import java.util.ArrayList;
import java.util.List;

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
 * RabbitMQ configuration for Consumer:
 * - Consumes directly from the 20 room queues (room.1 ~ room.20)
 * - No separate node queue needed — the room queues ARE the consumption point
 * - Dead Letter Exchange for failed messages
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "chat.exchange";
    public static final String DLX_EXCHANGE = "chat.dlx";
    public static final String DLQ_NAME = "chat.dead-letter";
    public static final String BROADCAST_EXCHANGE = "chat.broadcast";
    public static final int ROOM_COUNT = 20;

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
    public FanoutExchange broadcastExchange() {
        return new FanoutExchange(BROADCAST_EXCHANGE, false, true); // Transient, auto-delete for performance
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
     * Declare all 20 room queues + bindings.
     * MUST match server-v2's declaration (same TTL, max-length, etc.)
     * to avoid PRECONDITION_FAILED errors.
     */
    @Bean
    public Declarables roomQueuesAndBindings(TopicExchange chatExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 1; i <= ROOM_COUNT; i++) {
            String queueName = "room." + i;
            Queue queue = QueueBuilder.durable(queueName)
                    .ttl(5_000)            // Messages expire after 5 seconds
                    .maxLength(1_000L)      // Max 1k messages per queue
                    .overflow(QueueBuilder.Overflow.dropHead)
                    .deadLetterExchange(DLX_EXCHANGE)
                    .build();
            Binding binding = BindingBuilder.bind(queue).to(chatExchange).with(queueName);
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    @org.springframework.beans.factory.annotation.Value("${consumer.rooms:*}")
    private String roomRange;

    /**
     * Generate the list of room queue names for @RabbitListener.
     * Supports "specific rooms" and "fair distribution" as per Assignment 2.
     * Use "*" for all rooms, or "1-10", "11-20" for sharding.
     */
    @Bean
    public String[] roomQueueNames() {
        if ("*".equals(roomRange)) {
            String[] names = new String[ROOM_COUNT];
            for (int i = 0; i < ROOM_COUNT; i++) names[i] = "room." + (i + 1);
            return names;
        }
        
        try {
            String[] parts = roomRange.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            List<String> list = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                if (i >= 1 && i <= ROOM_COUNT) list.add("room." + i);
            }
            return list.toArray(new String[0]);
        } catch (Exception e) {
            return new String[]{"room.1"}; // Fallback
        }
    }
}
