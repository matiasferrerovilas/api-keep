package api.m2.file.events;

import java.time.LocalDateTime;

/**
 * Mirrors api-identity's {@code InvitationAcceptedEvent} payload (same field names, so Jackson's
 * default record deserialization lines up) — published to the {@code identity.topic} exchange
 * when someone accepts a workspace invitation, consumed here to push a live notification to the
 * other members instead of them finding out on their next unrelated request.
 */
public record InvitationAcceptedReceivedEvent(
        Long invitationId,
        Long workspaceId,
        String workspaceName,
        String acceptedByEmail,
        LocalDateTime acceptedAt) {
}
