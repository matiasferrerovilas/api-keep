package api.m2.file.mappers;

import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.record.FileDTO;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface FileDTOMapper {

    default FileDTO toFileDTO(FileEntity file, Map<Long, List<FileEntity>> childrenByParentId,
                               Map<Long, List<String>> shareWithByFileId) {
        List<FileDTO> children = file.getType() == FileType.FOLDER
                ? childrenByParentId.getOrDefault(file.getId(), List.of())
                        .stream()
                        .sorted(Comparator.comparing(FileEntity::getName))
                        .map(child -> toFileDTO(child, childrenByParentId, shareWithByFileId))
                        .toList()
                : null;

        List<String> shareWith = shareWithByFileId.getOrDefault(file.getId(), List.of());

        return toFileDTO(file, children, shareWith);
    }

    default FileDTO toFileDTO(FileEntity file, List<FileDTO> children, List<String> shareWith) {
        return FileDTO.builder()
                .id(file.getId().toString())
                .name(file.getName())
                .children(children)
                .shareWith(shareWith)
                .metadata(FileDTO.Metadata.builder()
                        .size(file.getSize())
                        .lastModified(file.getUpdatedAt())
                        .createdAt(file.getCreatedAt())
                        .type(file.getType())
                        .contentType(file.getContentType())
                        .checksum(file.getChecksum())
                        .favorite(file.isFavorite())
                        .lastAccessedAt(file.getLastAccessedAt())
                        .build())
                .build();
    }
}
