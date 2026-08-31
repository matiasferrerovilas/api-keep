package api.m2.file.service.publishing;

import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.constants.WebSocketTopics;
import api.m2.file.enums.EventType;
import api.m2.file.enums.InvitationStatus;
import api.m2.file.events.InvitationAcceptedReceivedEvent;
import api.m2.file.events.InvitationReceivedEvent;
import api.m2.file.record.events.EventWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import static api.m2.file.configuration.RabbitConfig.QUEUE_INVITATION_ACCEPTED;
import static api.m2.file.configuration.RabbitConfig.QUEUE_INVITATION_RECEIVED;

/**
 * Consumes the invitation-sent/accepted events published by api-identity (see
 * {@code RabbitConfig}) and pushes them over STOMP so the frontend reacts live instead of
 * polling GET /v1/workspace/invitations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvitationPublishServiceWebSocket {

    private final SimpMessagingTemplate messagingTemplate;

    @RabbitListener(queues = QUEUE_INVITATION_RECEIVED)
    public void onInvitationReceived(InvitationReceivedEvent event) {
        log.debug("Invitación recibida desde api-identity para {}", event.invitedUserEmail());
        // El frontend cachea esto igual que la respuesta de GET /v1/workspace/invitations
        // (WorkspaceInvitationDTO: id/workspaceId/workspaceName/invitedByEmail/status/role/createdAt),
        // así que el payload debe tener esa misma forma en vez del RabbitMQ event crudo — de
        // lo contrario "id" llega undefined y el PATCH de aceptar/rechazar rompe en el backend
        // (bug ya visto y corregido en api-movements para el mismo evento).
        var invitationDTO = new WorkspaceInvitationDTO(
                event.invitationId(),
                event.workspaceId(),
                event.workspaceName(),
                event.invitedByEmail(),
                InvitationStatus.PENDING,
                event.role(),
                event.createdAt());
        String topic = WebSocketTopics.invitationsNew(event.invitedUserEmail());
        messagingTemplate.convertAndSend(topic, new EventWrapper<>(EventType.INVITATION_ADDED, invitationDTO));
    }

    /**
     * Alguien aceptó una invitación y se sumó a un workspace compartido — avisamos a quien tenga
     * ese workspace abierto para que refresque la lista de miembros, en vez de mostrar datos
     * desactualizados hasta el próximo refetch.
     */
    @RabbitListener(queues = QUEUE_INVITATION_ACCEPTED)
    public void onInvitationAccepted(InvitationAcceptedReceivedEvent event) {
        log.debug("Invitación {} aceptada por {} en workspace {}",
                event.invitationId(), event.acceptedByEmail(), event.workspaceId());
        String topic = WebSocketTopics.workspaceMembersUpdate(event.workspaceId());
        messagingTemplate.convertAndSend(topic, new EventWrapper<>(EventType.MEMBERSHIP_UPDATED, event));
    }
}
