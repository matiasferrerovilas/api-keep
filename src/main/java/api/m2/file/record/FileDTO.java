package api.m2.file.record;

import api.m2.file.enums.FileType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record FileDTO(
        String id,
        String name,
        List<FileDTO> children,
        List<String> shareWith,
        Metadata metadata
) {

    @Builder
    public record Metadata(
            Long size,
            LocalDateTime lastModified,
            LocalDateTime createdAt,
            FileType type,
            String contentType,
            String checksum,
            boolean favorite,
            LocalDateTime lastAccessedAt
    ) {
    }
}
