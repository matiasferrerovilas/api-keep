package api.m2.file.clients.identity.response;

import api.m2.file.enums.InvitationStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

public record WorkspaceSentInvitationDTO(
        Long id,
        Long workspaceId,
        String workspaceName,
        String invitedUserEmail,
        InvitationStatus status,
        LocalDateTime createdAt) implements Serializable {
}
