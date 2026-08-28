package api.m2.file.repository;

import api.m2.file.entity.UserFileShare;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFileShareRepository extends JpaRepository<UserFileShare, Long> {
    List<UserFileShare> findByFileId(Long fileId);

    List<UserFileShare> findByFileIdIn(List<Long> fileIds);

    Optional<UserFileShare> findByFileIdAndSharedWithUserId(Long fileId, Long sharedWithUserId);

    boolean existsByFileIdAndSharedWithUserId(Long fileId, Long sharedWithUserId);

    /** Only currently-active grants (never expires, or expiry still in the future) — used both by
     * the access-control fallback and by "shared with me". */
    @Query("SELECT s FROM UserFileShare s WHERE s.sharedWithUserId = :userId "
            + "AND (s.expiresAt IS NULL OR s.expiresAt > :now)")
    List<UserFileShare> findActiveBySharedWithUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    List<UserFileShare> findByExpiresAtBefore(LocalDateTime cutoff);

    /** Grants expiring within the given window that haven't been reminded about yet — see
     * {@code FileService.sendExpiringShareReminders}. */
    List<UserFileShare> findByExpiresAtBetweenAndExpiryReminderSentAtIsNull(LocalDateTime from, LocalDateTime to);
}
