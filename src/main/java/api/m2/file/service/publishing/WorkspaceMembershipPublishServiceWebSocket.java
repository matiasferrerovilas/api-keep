package api.m2.file.service.publishing;

import api.m2.file.constants.WebSocketTopics;
import api.m2.file.enums.EventType;
import api.m2.file.events.MemberRemovedReceivedEvent;
import api.m2.file.record.events.EventWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import static api.m2.file.configuration.RabbitConfig.QUEUE_MEMBER_REMOVED;

/**
 * Consumes the member-removed event published by api-identity when someone is kicked from a
 * workspace, and pushes it over STOMP so the removed user's UI reacts live (drop the workspace
 * from their list) instead of only finding out the next time some unrelated request 404s.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceMembershipPublishServiceWebSocket {

    private final SimpMessagingTemplate messagingTemplate;

    @RabbitListener(queues = QUEUE_MEMBER_REMOVED)
    public void onMemberRemoved(MemberRemovedReceivedEvent event) {
        log.debug("{} fue eliminado del workspace {} (por {})",
                event.removedUserEmail(), event.workspaceId(), event.removedByEmail());
        String topic = WebSocketTopics.membershipRemoved(event.removedUserEmail());
        messagingTemplate.convertAndSend(topic, new EventWrapper<>(EventType.WORKSPACE_LEFT, event));
    }
}
