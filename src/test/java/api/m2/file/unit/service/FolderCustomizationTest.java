package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.FileDTO;
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
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers custom folder color/icon: set, clear, and the not-a-folder guard. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderCustomizationTest {

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
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());
    }

    @Test
    void setFolderCustomization_setsColorAndIconOnAFolder() {
        FileEntity folder = folderAt(1L, "Viajes");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));

        FileDTO result = fileService.setFolderCustomization(1L, "#4a6fa5", "rocket");

        assertThat(folder.getFolderColor()).isEqualTo("#4a6fa5");
        assertThat(folder.getFolderIcon()).isEqualTo("rocket");
        assertThat(result.metadata().folderColor()).isEqualTo("#4a6fa5");
        assertThat(result.metadata().folderIcon()).isEqualTo("rocket");
        verify(fileRepository).save(folder);
    }

    @Test
    void setFolderCustomization_clearsColorAndIconWhenNullPassed() {
        FileEntity folder = folderAt(1L, "Viajes");
        folder.setFolderColor("#4a6fa5");
        folder.setFolderIcon("rocket");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));

        FileDTO result = fileService.setFolderCustomization(1L, null, null);

        assertThat(folder.getFolderColor()).isNull();
        assertThat(folder.getFolderIcon()).isNull();
        assertThat(result.metadata().folderColor()).isNull();
        assertThat(result.metadata().folderIcon()).isNull();
    }

    @Test
    void setFolderCustomization_throwsWhenTheNodeDoesNotExist() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.setFolderCustomization(99L, "#4a6fa5", "rocket"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void setFolderCustomization_throwsWhenTheNodeIsNotAFolder() {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .workspaceId(5L)
                .parentId(2L)
                .name("doc.txt")
                .type(FileType.FILE)
                .build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.setFolderCustomization(1L, "#4a6fa5", "rocket"))
                .isInstanceOf(BusinessException.class);
    }

    private FileEntity folderAt(Long id, String name) {
        return FileEntity.builder()
                .id(id)
                .workspaceId(5L)
                .parentId(2L)
                .name(name)
                .type(FileType.FOLDER)
                .build();
    }
}
