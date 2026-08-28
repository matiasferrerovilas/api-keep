package api.m2.file.service.publishing;

import api.m2.file.constants.WebSocketTopics;
import api.m2.file.record.events.EventWrapper;
import api.m2.file.record.events.UserFileShareEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges UserSharingService's create/expiring-reminder events to the STOMP broker — previously
 * a recipient only found out about a share (or that it was about to expire) by opening
 * "Compartido conmigo" and looking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSharePublishServiceWebSocket {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserFileShareEvent(UserFileShareEvent event) {
        String topic = WebSocketTopics.userSharesNew(event.sharedWithEmail());
        log.debug("Publicando evento {} de share de usuario en {}", event.eventType(), topic);
        messagingTemplate.convertAndSend(topic, new EventWrapper<>(event.eventType(), event));
    }
}
