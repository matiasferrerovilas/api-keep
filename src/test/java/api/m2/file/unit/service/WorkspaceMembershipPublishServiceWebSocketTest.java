package api.m2.file.unit.service;

import api.m2.file.enums.EventType;
import api.m2.file.events.MemberRemovedReceivedEvent;
import api.m2.file.record.events.EventWrapper;
import api.m2.file.service.publishing.WorkspaceMembershipPublishServiceWebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkspaceMembershipPublishServiceWebSocketTest {

    @Mock
    SimpMessagingTemplate messagingTemplate;

    WorkspaceMembershipPublishServiceWebSocket service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceMembershipPublishServiceWebSocket(messagingTemplate);
    }

    @Test
    void onMemberRemoved_publishesToTheRemovedUsersEmailScopedTopic() {
        var event = new MemberRemovedReceivedEvent(10L, "Casa", "owner@test.com", "removed@test.com", LocalDateTime.now());

        service.onMemberRemoved(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventWrapper<MemberRemovedReceivedEvent>> captor = ArgumentCaptor.forClass(EventWrapper.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/membership/removed@test.com/remove"), captor.capture());

        var wrapper = captor.getValue();
        assertThat(wrapper.eventType()).isEqualTo(EventType.WORKSPACE_LEFT);
        assertThat(wrapper.message()).isEqualTo(event);
    }
}
