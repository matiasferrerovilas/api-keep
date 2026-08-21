package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.enums.EventType;
import api.m2.file.enums.InvitationStatus;
import api.m2.file.events.InvitationAcceptedReceivedEvent;
import api.m2.file.events.InvitationReceivedEvent;
import api.m2.file.record.events.EventWrapper;
import api.m2.file.service.publishing.InvitationPublishServiceWebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InvitationPublishServiceWebSocketTest {

    @Mock
    SimpMessagingTemplate messagingTemplate;

    InvitationPublishServiceWebSocket service;

    @BeforeEach
    void setUp() {
        service = new InvitationPublishServiceWebSocket(messagingTemplate);
    }

    @Test
    void onInvitationReceived_publishesToTheInvitedUsersEmailScopedTopicAsAWorkspaceInvitationDTO() {
        var createdAt = LocalDateTime.now();
        var event = new InvitationReceivedEvent(1L, 10L, "Casa", "owner@example.com", "invited@example.com", createdAt);

        service.onInvitationReceived(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventWrapper<WorkspaceInvitationDTO>> captor = ArgumentCaptor.forClass(EventWrapper.class);
        verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/invitations/invited@example.com/new"),
                captor.capture());

        var wrapper = captor.getValue();
        assertThat(wrapper.eventType()).isEqualTo(EventType.INVITATION_ADDED);
        var dto = wrapper.message();
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.workspaceId()).isEqualTo(10L);
        assertThat(dto.workspaceName()).isEqualTo("Casa");
        assertThat(dto.invitedByEmail()).isEqualTo("owner@example.com");
        assertThat(dto.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(dto.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void onInvitationAccepted_publishesToTheWorkspacesMembersTopic() {
        var event = new InvitationAcceptedReceivedEvent(1L, 10L, "Casa", "invited@example.com", LocalDateTime.now());

        service.onInvitationAccepted(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventWrapper<InvitationAcceptedReceivedEvent>> captor = ArgumentCaptor.forClass(EventWrapper.class);
        verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/workspace/10/members/update"),
                captor.capture());

        var wrapper = captor.getValue();
        assertThat(wrapper.eventType()).isEqualTo(EventType.MEMBERSHIP_UPDATED);
        assertThat(wrapper.message()).isEqualTo(event);
    }
}
