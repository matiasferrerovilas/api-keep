package api.m2.file.entity;

import api.m2.file.enums.FileActivityAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** One row per meaningful thing that happened to a file/folder — who uploaded, renamed, moved,
 * deleted, restored, or (un)shared it, and when. Deliberately not derived from the generic
 * FileTreeChangedEvent (FILE_ADDED/FILE_UPDATED/FILE_DELETED can't tell a move apart from a
 * rename, or either from a favorite toggle), so this is written explicitly at each call site that
 * represents a distinct, nameable action. FK cascades with the file, same as AppFileShare/
 * UserFileShare — the activity trail goes away once the file is actually purged from trash, not
 * kept around indefinitely for a file that no longer exists. */
@Entity
@Table(name = "file_activity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileActivityAction action;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    /** Denormalized, like UserFileShare.sharedWithEmail — no local users table to join against. */
    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    /** Snapshot of the name at the time of the action — a later rename shouldn't rewrite history. */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** Free-form extra context (e.g. the target folder for a move, the email for a share). Null
     * when the action needs none. */
    @Column(length = 500)
    private String detail;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
