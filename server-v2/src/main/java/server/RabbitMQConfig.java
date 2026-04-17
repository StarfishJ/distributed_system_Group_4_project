package server;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for Server-v2 (producer + local broadcast subscriber):
 * - Topic exchange chat.exchange
 * - 20 durable room queues (room.1 ~ room.20)
 * - Dead Letter Exchange
 *
 * Broadcast subscription: default fanout {@link #BROADCAST_EXCHANGE}; optional targeted
 * {@link BroadcastRouting#TOPIC_EXCHANGE} + durable queue {@code broadcast.srv.{suffix}} when
 * {@code server.broadcast.targeted=true}.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "chat.exchange";
    public static final String DLX_EXCHANGE = "chat.dlx";
    public static final String DLQ_NAME = "chat.dead-letter";
    public static final String POISON_DLQ_NAME = "poison.dlq";
    public static final String DLX_ROUTING_KEY_BUSINESS = "business.dlq";
    public static final String DLX_ROUTING_KEY_POISON = "poison.dlq";
    public static final String BROADCAST_EXCHANGE = "chat.broadcast";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange chatExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
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

    /**
     * Declare all 20 room queues + bindings (assignment requirement).
     */
    @Bean
    public Declarables roomQueuesAndBindings(TopicExchange chatExchange) {
        List<Declarable> declarables = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
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

    /**
     * Anonymous fanout queue (default): receives all consumer fanout broadcasts.
     */
    @Configuration
    @ConditionalOnProperty(name = "server.broadcast.targeted", havingValue = "false", matchIfMissing = true)
    static class FanoutBroadcastSubscription {

        @Bean
        public FanoutExchange broadcastExchange() {
            return new FanoutExchange(BROADCAST_EXCHANGE, false, true);
        }

        @Bean
        public Queue broadcastSubscriptionQueue() {
            return QueueBuilder.nonDurable()
                    .autoDelete()
                    .exclusive()
                    .build();
        }

        @Bean
        public Binding broadcastSubscriptionBinding(Queue broadcastSubscriptionQueue, FanoutExchange broadcastExchange) {
            return BindingBuilder.bind(broadcastSubscriptionQueue).to(broadcastExchange);
        }
    }

    /**
     * Targeted topic subscription: durable queue bound to {@link BroadcastRouting#TOPIC_EXCHANGE} with
     * {@link BroadcastRouting#topicRoutingKey(String)}.
     */
    @Configuration
    @ConditionalOnProperty(name = "server.broadcast.targeted", havingValue = "true")
    static class TargetedBroadcastSubscription {

        @Bean
        public TopicExchange broadcastTopicExchange() {
            return new TopicExchange(BroadcastRouting.TOPIC_EXCHANGE, true, false);
        }

        @Bean
        public Queue broadcastSubscriptionQueue(
                @Qualifier(ServerIdentityConfiguration.SERVER_INSTANCE_ID_BEAN) String serverInstanceIdentity) {
            String suffix = BroadcastRouting.sanitizeInstanceSuffix(serverInstanceIdentity);
            return QueueBuilder.durable("broadcast.srv." + suffix).build();
        }

        @Bean
        public Binding broadcastSubscriptionBinding(
                Queue broadcastSubscriptionQueue,
                TopicExchange broadcastTopicExchange,
                @Qualifier(ServerIdentityConfiguration.SERVER_INSTANCE_ID_BEAN) String serverInstanceIdentity) {
            String rk = BroadcastRouting.topicRoutingKey(serverInstanceIdentity);
            return BindingBuilder.bind(broadcastSubscriptionQueue).to(broadcastTopicExchange).with(rk);
        }
    }
}
