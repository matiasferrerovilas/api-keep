package api.m2.file.events;

import api.m2.file.enums.WorkspaceRole;

import java.time.LocalDateTime;

/**
 * Mirrors api-identity's {@code InvitationCreatedEvent} payload (same field names, so Jackson's
 * default record deserialization lines up) — published to the {@code identity.topic} exchange
 * when someone is invited to a workspace, consumed here to push a live notification instead of
 * making the invited user poll GET /v1/workspace/invitations.
 */
public record InvitationReceivedEvent(
        Long invitationId,
        Long workspaceId,
        String workspaceName,
        String invitedByEmail,
        String invitedUserEmail,
        WorkspaceRole role,
        LocalDateTime createdAt) {
}
