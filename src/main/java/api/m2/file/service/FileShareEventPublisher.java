package api.m2.file.service;

import api.m2.file.configuration.RabbitConfig;
import api.m2.file.events.FileSharedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileShareEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishFileShared(FileSharedEvent event) {
        log.debug("Publicando share del archivo {} con {}", event.fileId(), event.apiName());
        rabbitTemplate.convertAndSend(
                RabbitConfig.AMQ_TOPIC_EXCHANGE,
                RabbitConfig.ROUTING_KEY_FILE_SHARED,
                event);
    }
}
