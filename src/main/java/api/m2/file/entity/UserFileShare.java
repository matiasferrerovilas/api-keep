package api.m2.file.entity;

import api.m2.file.enums.SharePermission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_file_shares",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"file_id", "shared_with_user_id"})
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFileShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "shared_with_user_id", nullable = false)
    private Long sharedWithUserId;

    /** Denormalized at share-creation time so listing grantees doesn't need a round trip back to
     * api-identity per row — this service has no local users table to join against. */
    @Column(name = "shared_with_email", nullable = false)
    private String sharedWithEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SharePermission permission;

    /** Null = no expiration. Non-null = the instant this share stops granting access — checked
     * inline wherever a share is consulted, not just at creation time. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Set once the "about to expire" reminder fires for the current {@link #expiresAt}, so the
     * hourly job doesn't send it again on every run. Cleared whenever {@link #expiresAt} changes
     * (see {@code UserSharingService.updateShare}) so extending a share re-arms the reminder for
     * the new date. */
    @Column(name = "expiry_reminder_sent_at")
    private LocalDateTime expiryReminderSentAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
