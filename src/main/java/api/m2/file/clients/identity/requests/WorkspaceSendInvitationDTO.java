package api.m2.file.clients.identity.requests;

import java.util.List;

public record WorkspaceSendInvitationDTO(Long workspaceId, List<String> emails) {
}
