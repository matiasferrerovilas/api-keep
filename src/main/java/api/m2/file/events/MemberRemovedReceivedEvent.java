package api.m2.file.events;

import java.time.LocalDateTime;

/**
 * Mirrors api-identity's {@code MemberRemovedEvent} payload (same field names, so Jackson's
 * default record deserialization lines up) — published to the {@code identity.topic} exchange
 * when a member is kicked from a workspace, consumed here to push a live notification to the
 * removed user instead of them finding out on their next unrelated request.
 */
public record MemberRemovedReceivedEvent(
        Long workspaceId,
        String workspaceName,
        String removedByEmail,
        String removedUserEmail,
        LocalDateTime removedAt) {
}
