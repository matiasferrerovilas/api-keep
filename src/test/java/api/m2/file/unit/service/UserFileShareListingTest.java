package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.EventType;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.events.UserFileShareEvent;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.repository.UserFileShareRepository;
import api.m2.file.repository.FileActivityRepository;
import api.m2.file.mappers.FileActivityMapper;
import api.m2.file.record.FileDTO;
import api.m2.file.service.FileActivityLogService;
import api.m2.file.service.FileService;
import api.m2.file.service.SourceAppResolver;
import api.m2.file.service.UserService;
import api.m2.file.service.storage.LocalFsStorageAdapter;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers "shared with me" (how a recipient discovers what was shared with them) and the
 * scheduled purge of expired user-file-shares. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserFileShareListingTest {

    @Mock
    FileRepository fileRepository;
    @Mock
    AppFileShareRepository appFileShareRepository;
    @Mock
    UserFileShareRepository userFileShareRepository;
    @Mock
    UserService userService;
    @Mock
    WorkspaceService workspaceService;
    @Mock
    FileDTOMapper fileDTOMapper;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    FileActivityRepository fileActivityRepository;
    @Mock
    FileActivityMapper fileActivityMapper;
    @Mock
    FileActivityLogService fileActivityLogService;

    @TempDir
    Path tempDir;

    FileService fileService;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(tempDir.toString(), DataSize.ofMegabytes(50), DataSize.ofGigabytes(5));
        fileService = new FileService(
                fileRepository,
                appFileShareRepository,
                userFileShareRepository,
                storageProperties,
                new LocalFsStorageAdapter(),
                fileDTOMapper,
                userService,
                workspaceService,
                new SourceAppResolver(),
                eventPublisher,
                fileActivityRepository,
                fileActivityMapper,
                fileActivityLogService);
        when(userService.getMe()).thenReturn(new UserMe(1L, "user@example.com", "Nombre", "Apellido", "PERSONAL", null));
    }

    @Test
    void listSharedWithMe_returnsTheFilesBehindEachActiveGrant() {
        FileEntity sharedFile = fileAt(10L, "reporte.pdf");
        UserFileShare share = UserFileShare.builder()
                .id(1L).fileId(10L).sharedWithUserId(1L).sharedWithEmail("user@example.com")
                .permission(SharePermission.READ).createdBy(2L).build();
        when(userFileShareRepository.findActiveBySharedWithUserId(eq(1L), any())).thenReturn(List.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(sharedFile));

        List<FileDTO> result = fileService.listSharedWithMe();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo("10");
        assertThat(result.getFirst().name()).isEqualTo("reporte.pdf");
    }

    @Test
    void listSharedWithMe_onlyAsksTheRepositoryForActiveGrants() {
        when(userFileShareRepository.findActiveBySharedWithUserId(eq(1L), any())).thenReturn(List.of());

        fileService.listSharedWithMe();

        // The repository query itself (findActiveBySharedWithUserId, not a plain findBySharedWithUserId)
        // is what excludes expired grants — nothing to filter again on this side.
        verify(userFileShareRepository, never()).findByFileId(anyLong());
    }

    @Test
    void purgeExpiredUserShares_deletesOnlySharesPastTheirExpiration() {
        UserFileShare expired = UserFileShare.builder().id(1L).fileId(10L).sharedWithUserId(1L)
                .sharedWithEmail("user@example.com").permission(SharePermission.READ)
                .expiresAt(LocalDateTime.now().minusDays(1)).createdBy(2L).build();
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        when(userFileShareRepository.findByExpiresAtBefore(cutoffCaptor.capture())).thenReturn(List.of(expired));

        fileService.purgeExpiredUserShares();

        verify(userFileShareRepository).deleteAll(List.of(expired));
        assertThat(cutoffCaptor.getValue()).isNotNull();
    }

    @Test
    void purgeExpiredUserShares_doesNothingWhenNothingHasExpired() {
        when(userFileShareRepository.findByExpiresAtBefore(any())).thenReturn(List.of());

        fileService.purgeExpiredUserShares();

        verify(userFileShareRepository, never()).deleteAll(any());
    }

    @Test
    void sendExpiringShareReminders_publishesAnEventAndMarksTheShareAsReminded() {
        FileEntity sharedFile = fileAt(10L, "reporte.pdf");
        UserFileShare expiringSoon = UserFileShare.builder().id(1L).fileId(10L).sharedWithUserId(1L)
                .sharedWithEmail("friend@example.com").permission(SharePermission.READ)
                .expiresAt(LocalDateTime.now().plusHours(12)).createdBy(2L).build();
        when(userFileShareRepository.findByExpiresAtBetweenAndExpiryReminderSentAtIsNull(any(), any()))
                .thenReturn(List.of(expiringSoon));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(sharedFile));

        fileService.sendExpiringShareReminders();

        ArgumentCaptor<UserFileShareEvent> captor = ArgumentCaptor.forClass(UserFileShareEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        UserFileShareEvent event = captor.getValue();
        assertThat(event.fileId()).isEqualTo(10L);
        assertThat(event.fileName()).isEqualTo("reporte.pdf");
        assertThat(event.sharedWithEmail()).isEqualTo("friend@example.com");
        assertThat(event.eventType()).isEqualTo(EventType.USER_FILE_SHARE_EXPIRING);
        assertThat(expiringSoon.getExpiryReminderSentAt()).isNotNull();
        verify(userFileShareRepository).saveAll(List.of(expiringSoon));
    }

    @Test
    void sendExpiringShareReminders_doesNothingWhenNothingIsExpiringSoon() {
        when(userFileShareRepository.findByExpiresAtBetweenAndExpiryReminderSentAtIsNull(any(), any()))
                .thenReturn(List.of());

        fileService.sendExpiringShareReminders();

        verify(eventPublisher, never()).publishEvent(any());
        verify(userFileShareRepository, never()).saveAll(any());
    }

    private FileEntity fileAt(Long id, String filename) {
        return FileEntity.builder()
                .id(id)
                .workspaceId(5L)
                .parentId(2L)
                .name(filename)
                .type(FileType.FILE)
                .build();
    }
}
