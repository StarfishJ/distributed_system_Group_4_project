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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

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
        return new FanoutExchange(BROADCAST_EXCHANGE, false, true);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ_NAME, true);
    }

    @Bean
    public Binding dlqBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    @Bean
    public Declarables roomQueuesAndBindings(TopicExchange chatExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 1; i <= ROOM_COUNT; i++) {
            String queueName = "room." + i;
            Queue queue = QueueBuilder.durable(queueName)
                    .ttl(5_000)
                    .maxLength(1_000L)
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

    @Bean
    public String[] roomQueueNames() {
        String raw = roomRange == null ? "*" : roomRange.trim();
        if (raw.isEmpty() || "*".equals(raw)) {
            return allRoomQueueNames();
        }
        try {
            String[] parts = raw.split("-");
            if (parts.length != 2) {
                log.warn("consumer.rooms must be '*' or 'start-end' (e.g. 1-5); got '{}', using all rooms", roomRange);
                return allRoomQueueNames();
            }
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            if (start > end) {
                int t = start;
                start = end;
                end = t;
            }
            List<String> list = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                if (i >= 1 && i <= ROOM_COUNT) {
                    list.add("room." + i);
                }
            }
            if (list.isEmpty()) {
                log.warn("consumer.rooms '{}' matched no queues in 1-{}, using all rooms", raw, ROOM_COUNT);
                return allRoomQueueNames();
            }
            log.info("This consumer instance will listen on {} room queues: {} .. {}", list.size(), list.get(0), list.get(list.size() - 1));
            return list.toArray(new String[0]);
        } catch (Exception e) {
            log.warn("Invalid consumer.rooms='{}', using all rooms: {}", roomRange, e.getMessage());
            return allRoomQueueNames();
        }
    }

    private static String[] allRoomQueueNames() {
        String[] names = new String[ROOM_COUNT];
        for (int i = 0; i < ROOM_COUNT; i++) {
            names[i] = "room." + (i + 1);
        }
        return names;
    }
}
