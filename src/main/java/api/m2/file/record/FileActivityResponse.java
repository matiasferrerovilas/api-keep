package api.m2.file.record;

import api.m2.file.enums.FileActivityAction;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record FileActivityResponse(
        Long id,
        FileActivityAction action,
        Long actorUserId,
        String actorEmail,
        String fileName,
        String detail,
        LocalDateTime createdAt
) {
}
