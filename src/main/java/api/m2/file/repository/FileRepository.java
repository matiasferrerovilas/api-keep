package api.m2.file.repository;

import api.m2.file.entity.FileEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNull(Long workspaceId);

    Optional<FileEntity> findByWorkspaceIdAndDeletedAtIsNullAndChecksum(Long workspaceId, String checksum);

    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNotNull(Long workspaceId);

    List<FileEntity> findByDeletedAtBefore(LocalDateTime cutoff);

    Optional<FileEntity> findByWorkspaceIdAndParentIdIsNull(Long workspaceId);
}
