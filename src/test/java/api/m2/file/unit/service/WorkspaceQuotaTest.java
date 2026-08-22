package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.WorkspaceUsageResponse;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.UserFileShareRepository;
import api.m2.file.repository.FileActivityRepository;
import api.m2.file.mappers.FileActivityMapper;
import api.m2.file.repository.FileRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the per-workspace storage quota: uploadFile rejects once currentUsage + incoming size
 * would exceed app.storage.workspace-quota, and getWorkspaceUsage reports the same two numbers
 * (usedBytes/quotaBytes) that fe-keep's usage indicator relies on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceQuotaTest {

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
        StorageProperties storageProperties = new StorageProperties(tempDir.toString(), DataSize.ofMegabytes(50), DataSize.ofBytes(20));
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
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());

        FileEntity root = FileEntity.builder().id(2L).workspaceId(5L).name("Home").type(FileType.FOLDER)
                .location(tempDir.toString()).build();
        when(fileRepository.findByWorkspaceIdAndParentIdIsNull(5L)).thenReturn(Optional.of(root));
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndChecksum(anyLong(), anyString())).thenReturn(Optional.empty());
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
    }

    @Test
    void uploadFile_rejectsWhenCurrentUsagePlusIncomingSizeExceedsTheQuota() {
        when(fileRepository.sumSizeByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(15L);
        var file = new MockMultipartFile("file", "grande.txt", "text/plain", "0123456789".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(5L, null, file))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cuota de almacenamiento");

        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void uploadFile_allowsWhenCurrentUsagePlusIncomingSizeIsAtOrUnderTheQuota() {
        when(fileRepository.sumSizeByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(10L);
        var file = new MockMultipartFile("file", "justo.txt", "text/plain", "0123456789".getBytes());

        var result = fileService.uploadFile(5L, null, file);

        assertThat(result.name()).isEqualTo("Justo.txt");
    }

    @Test
    void getWorkspaceUsage_reportsUsedAndQuotaBytes() {
        when(fileRepository.sumSizeByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(12L);

        WorkspaceUsageResponse usage = fileService.getWorkspaceUsage(5L);

        assertThat(usage.usedBytes()).isEqualTo(12L);
        assertThat(usage.quotaBytes()).isEqualTo(20L);
    }
}
