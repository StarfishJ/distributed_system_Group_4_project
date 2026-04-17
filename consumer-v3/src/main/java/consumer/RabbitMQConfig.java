package consumer;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.AcknowledgeMode;

@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    public static final String EXCHANGE_NAME = "chat.exchange";
    public static final String DLX_EXCHANGE = "chat.dlx";
    public static final String DLQ_NAME = "chat.dead-letter";
    /** Poison / TTL / maxlen / reject from room queues — bound with {@link #DLX_ROUTING_KEY_POISON}. */
    public static final String POISON_DLQ_NAME = "poison.dlq";
    /** Routing keys on {@link #DLX_EXCHANGE} (Direct). */
    public static final String DLX_ROUTING_KEY_BUSINESS = "business.dlq";
    public static final String DLX_ROUTING_KEY_POISON = "poison.dlq";
    /**
     * DB batch replay path (after {@link DeadLetterPublisher} exhausts in-process retries).
     * Separate from {@link #DLQ_NAME} where poison/TTL overflow messages land from room queues.
     */
    public static final String DB_REPLAY_EXCHANGE = "chat.db-replay";
    public static final String DB_REPLAY_QUEUE = "chat.db-replay";
    public static final String DB_REPLAY_ROUTING_KEY = "batch.failed";
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

    /**
     * Direct DLX: route by routing key — {@link #DLQ_NAME} ← {@code business.dlq}, {@link #POISON_DLQ_NAME} ← {@code poison.dlq}.
     * Room queues set {@code x-dead-letter-routing-key} to {@link #DLX_ROUTING_KEY_POISON} so broker dead-letters land on poison only.
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public FanoutExchange broadcastExchange() {
        return new FanoutExchange(BROADCAST_EXCHANGE, false, true);
    }

    /** Targeted broadcast (optional); Consumer publishes per-server when {@code consumer.broadcast.targeted=true} and Redis presence is available. */
    @Bean
    public TopicExchange broadcastTopicExchange() {
        return new TopicExchange(BroadcastRouting.TOPIC_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(DLQ_NAME, true);
    }

    @Bean
    public Queue poisonDlqQueue() {
        return new Queue(POISON_DLQ_NAME, true);
    }

    @Bean
    public Binding businessDlqBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLX_ROUTING_KEY_BUSINESS);
    }

    @Bean
    public Binding poisonDlqBinding(Queue poisonDlqQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(poisonDlqQueue).to(deadLetterExchange).with(DLX_ROUTING_KEY_POISON);
    }

    @Bean
    public DirectExchange dbReplayExchange() {
        return new DirectExchange(DB_REPLAY_EXCHANGE, true, false);
    }

    @Bean
    public Queue dbReplayQueue() {
        return new Queue(DB_REPLAY_QUEUE, true);
    }

    @Bean
    public Binding dbReplayBinding(Queue dbReplayQueue, DirectExchange dbReplayExchange) {
        return BindingBuilder.bind(dbReplayQueue).to(dbReplayExchange).with(DB_REPLAY_ROUTING_KEY);
    }

    /**
     * Single-threaded, prefetch 1: replays DB batches gently after outages.
     */
    @Bean(name = "dlqReplayListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory dlqReplayListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${consumer.dlq-replay.prefetch:1}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    @Bean(name = "deadLetterAuditListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory deadLetterAuditListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${consumer.dead-letter-audit.prefetch:1}") int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setPrefetchCount(Math.max(1, prefetch));
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    @Bean
    public Declarables roomQueuesAndBindings(TopicExchange chatExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 1; i <= ROOM_COUNT; i++) {
            String queueName = "room." + i;
            Queue queue = QueueBuilder.durable(queueName)
                    .maxLength(1_000L)
                    .overflow(QueueBuilder.Overflow.rejectPublish)
                    .deadLetterExchange(DLX_EXCHANGE)
                    .deadLetterRoutingKey(DLX_ROUTING_KEY_POISON)
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
