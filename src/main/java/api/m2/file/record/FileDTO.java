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
            LocalDateTime lastAccessedAt,
            String folderColor,
            String folderIcon,
            /** How many people (not apps) this node is currently shared with — just a signal for
             * the owner's card UI, the full grantee list is fetched on demand when needed. */
            int sharedWithUserCount
    ) {
    }
}
