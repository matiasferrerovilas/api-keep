package api.m2.file.record;

import api.m2.file.enums.SharePermission;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record UserFileShareResponse(
        Long id,
        Long fileId,
        Long sharedWithUserId,
        String sharedWithEmail,
        SharePermission permission,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
