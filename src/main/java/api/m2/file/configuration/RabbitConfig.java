package api.m2.file.configuration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

@Configuration
public class RabbitConfig {
    public static final String AMQ_TOPIC_EXCHANGE = "file-sharing.topic";
    public static final String ROUTING_KEY_FILE_SHARED = "file.shared";

    // api-identity owns this exchange; we only declare it here (same name/type) so our binding
    // below works regardless of which service starts first, and bind our own durable queues to
    // it — mirrors the identical setup in api-movements.
    public static final String IDENTITY_TOPIC_EXCHANGE = "identity.topic";
    public static final String QUEUE_INVITATION_RECEIVED = "keep.invitation.received";
    public static final String QUEUE_INVITATION_ACCEPTED = "keep.invitation.accepted";
    public static final String QUEUE_MEMBER_REMOVED = "keep.member.removed";
    private static final String ROUTING_KEY_INVITATION_SENT = "identity.invitation.sent";
    private static final String ROUTING_KEY_INVITATION_ACCEPTED = "identity.invitation.accepted";
    private static final String ROUTING_KEY_MEMBER_REMOVED = "identity.member.removed";

    @Bean
    public JacksonJsonMessageConverter jackson2JsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JacksonJsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        factory.setAfterReceivePostProcessors(message -> {
            var props = message.getMessageProperties();

            Optional.of(props.getContentType())
                    .orElseGet(() -> {
                        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                        return null;
                    });

            Optional.of(props.getPriority())
                    .orElseGet(() -> {
                        props.setPriority(0);
                        return null;
                    });

            return message;
        });

        return factory;
    }
    @Bean
    TopicExchange exchange() {
        return new TopicExchange(AMQ_TOPIC_EXCHANGE);
    }
    @Bean
    TopicExchange identityTopicExchange() {
        return new TopicExchange(IDENTITY_TOPIC_EXCHANGE);
    }
    @Bean
    Queue invitationReceivedQueue() {
        return QueueBuilder.durable(QUEUE_INVITATION_RECEIVED).build();
    }
    @Bean
    Binding invitationReceivedBinding() {
        return BindingBuilder
                .bind(invitationReceivedQueue())
                .to(identityTopicExchange())
                .with(ROUTING_KEY_INVITATION_SENT);
    }
    @Bean
    Queue invitationAcceptedQueue() {
        return QueueBuilder.durable(QUEUE_INVITATION_ACCEPTED).build();
    }
    @Bean
    Binding invitationAcceptedBinding() {
        return BindingBuilder
                .bind(invitationAcceptedQueue())
                .to(identityTopicExchange())
                .with(ROUTING_KEY_INVITATION_ACCEPTED);
    }
    @Bean
    Queue memberRemovedQueue() {
        return QueueBuilder.durable(QUEUE_MEMBER_REMOVED).build();
    }
    @Bean
    Binding memberRemovedBinding() {
        return BindingBuilder
                .bind(memberRemovedQueue())
                .to(identityTopicExchange())
                .with(ROUTING_KEY_MEMBER_REMOVED);
    }
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }
}
