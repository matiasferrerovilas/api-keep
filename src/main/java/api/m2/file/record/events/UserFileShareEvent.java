package api.m2.file.record.events;

import api.m2.file.enums.EventType;
import api.m2.file.enums.SharePermission;

import java.time.LocalDateTime;

/**
 * Published when a user-to-user share is created ({@link EventType#USER_FILE_SHARED}) or is
 * about to expire ({@link EventType#USER_FILE_SHARE_EXPIRING}); consumed by
 * {@code UserSharePublishServiceWebSocket} to push it live to the recipient instead of requiring
 * them to open "Compartido conmigo" and look. {@code sharedByEmail} is null for the expiring
 * reminder — {@code FileService}'s scheduled job only has the creator's numeric id at that point,
 * not worth an extra lookup just for display context.
 */
public record UserFileShareEvent(
        Long shareId,
        Long fileId,
        String fileName,
        String sharedWithEmail,
        String sharedByEmail,
        SharePermission permission,
        LocalDateTime expiresAt,
        EventType eventType) {
}
