package api.m2.file.repository;

import api.m2.file.entity.FileEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNull(Long workspaceId);

    Optional<FileEntity> findByWorkspaceIdAndDeletedAtIsNullAndChecksum(Long workspaceId, String checksum);

    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNotNull(Long workspaceId);

    List<FileEntity> findByDeletedAtBefore(LocalDateTime cutoff);

    Optional<FileEntity> findByWorkspaceIdAndParentIdIsNull(Long workspaceId);

    boolean existsByWorkspaceId(Long workspaceId);

    // Solo FILE (no FOLDER, que no ocupa espacio propio) y no borrado (la papelera sigue ocupando
    // disco hasta que se purga, pero no debe contar contra la cuota mientras el usuario decide
    // si la restaura).
    @Query("select coalesce(sum(f.size), 0) from FileEntity f "
            + "where f.workspaceId = :workspaceId and f.deletedAt is null and f.type = api.m2.file.enums.FileType.FILE")
    long sumSizeByWorkspaceIdAndDeletedAtIsNull(@Param("workspaceId") Long workspaceId);

    // Case-insensitive LIKE against name and (when present) the extracted text content, scoped
    // to the workspace. The workspace_id+name composite index keeps the common case (name match)
    // fast; content is only ever populated for .txt/.md, so that half of the OR touches a small
    // slice of rows in practice at this data scale.
    @Query("select f from FileEntity f where f.workspaceId = :workspaceId and f.deletedAt is null "
            + "and (lower(f.name) like lower(concat('%', :query, '%')) "
            + "or (f.content is not null and lower(f.content) like lower(concat('%', :query, '%')))) "
            + "order by f.name")
    List<FileEntity> searchByWorkspaceIdAndQuery(@Param("workspaceId") Long workspaceId, @Param("query") String query);

    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNullAndFavoriteTrue(Long workspaceId);

    List<FileEntity> findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(
            Long workspaceId, Pageable pageable);
}
