package api.m2.file.service.publishing;

import api.m2.file.constants.WebSocketTopics;
import api.m2.file.record.events.EventWrapper;
import api.m2.file.record.events.FileTreeChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges FileService's tree mutations to the STOMP broker that WebSocketConfig already exposes
 * at /ws — previously nothing ever called messagingTemplate.convertAndSend, so clients had no way
 * to learn about changes made in another session without refetching the whole tree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileTreePublishServiceWebSocket {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFileTreeChanged(FileTreeChangedEvent event) {
        String topic = switch (event.eventType()) {
            case FILE_ADDED -> WebSocketTopics.filesNew(event.workspaceId());
            case FILE_UPDATED, FILE_SHARED -> WebSocketTopics.filesUpdate(event.workspaceId());
            case FILE_DELETED -> WebSocketTopics.filesDelete(event.workspaceId());
        };
        log.debug("Publicando cambio de árbol de archivos en {}", topic);
        messagingTemplate.convertAndSend(topic, new EventWrapper<>(event.eventType(), event.file()));
    }
}
