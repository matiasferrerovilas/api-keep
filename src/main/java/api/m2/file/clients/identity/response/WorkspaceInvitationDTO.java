package api.m2.file.clients.identity.response;

import api.m2.file.enums.InvitationStatus;
import api.m2.file.enums.WorkspaceRole;

import java.io.Serializable;
import java.time.LocalDateTime;

public record WorkspaceInvitationDTO(
        Long id,
        Long workspaceId,
        String workspaceName,
        String invitedByEmail,
        InvitationStatus status,
        WorkspaceRole role,
        LocalDateTime createdAt) implements Serializable {
}
