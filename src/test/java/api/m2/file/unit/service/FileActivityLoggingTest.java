package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileActivity;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.mappers.FileActivityMapper;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.FileActivityResponse;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileActivityRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.repository.UserFileShareRepository;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the file/folder activity log: FileActivityLogService.record persisting a row, each
 * mutation site (upload/rename/move/delete/restore) recording the right action, and getActivity
 * being gated the same as reading the file itself. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileActivityLoggingTest {

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
    void uploadFile_recordsAnUploadedActivity() {
        FileEntity root = FileEntity.builder().id(2L).workspaceId(5L).name("Home").type(FileType.FOLDER)
                .location(tempDir.toString()).build();
        when(fileRepository.findByWorkspaceIdAndParentIdIsNull(5L)).thenReturn(Optional.of(root));
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
        var multipartFile = new MockMultipartFile("file", "recibo.txt", "text/plain", "hola".getBytes());

        fileService.uploadFile(5L, null, multipartFile);

        verify(fileActivityLogService).record(99L, 5L, FileActivityAction.UPLOADED, "Recibo.txt", 1L, "user@example.com", null);
    }

    @Test
    void renameNode_recordsARenamedActivityWithTheOldName() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.renameNode(1L, "renombrado.txt");

        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.RENAMED, "renombrado.txt",
                1L, "user@example.com", "de 'doc.txt'");
    }

    @Test
    void deleteNode_recordsADeletedActivity() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.deleteNode(1L);

        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.DELETED, "doc.txt", 1L, "user@example.com", null);
    }

    @Test
    void restoreNode_recordsARestoredActivity() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        file.setDeletedAt(java.time.LocalDateTime.now());
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.restoreNode(1L);

        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.RESTORED, "doc.txt", 1L, "user@example.com", null);
    }

    @Test
    void moveNode_recordsAMovedActivityNamingTheTargetFolder() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        FileEntity newParent = FileEntity.builder().id(3L).workspaceId(5L).name("Fotos").type(FileType.FOLDER)
                .location(tempDir.resolve("Fotos").toString()).build();
        try {
            Files.createDirectory(tempDir.resolve("Fotos"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(3L)).thenReturn(Optional.of(newParent));

        fileService.moveNode(1L, 3L);

        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.MOVED, "doc.txt",
                1L, "user@example.com", "a 'Fotos'");
    }

    @Test
    void getActivity_returnsTheTimelineForAFileTheCallerCanRead() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        FileActivity activity = FileActivity.builder().id(1L).fileId(1L).workspaceId(5L)
                .action(FileActivityAction.UPLOADED).actorUserId(1L).actorEmail("user@example.com")
                .fileName("doc.txt").build();
        when(fileActivityRepository.findByFileIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(activity));
        when(fileActivityMapper.toResponse(activity)).thenReturn(
                FileActivityResponse.builder().id(1L).action(FileActivityAction.UPLOADED)
                        .actorEmail("user@example.com").fileName("doc.txt").build());

        List<FileActivityResponse> result = fileService.getActivity(1L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().action()).isEqualTo(FileActivityAction.UPLOADED);
    }

    @Test
    void getActivity_deniesAccessTheSameWayReadingTheFileWould() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        doThrow(new PermissionDeniedException("No tienes permiso para operar sobre este recurso"))
                .when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());

        assertThatThrownBy(() -> fileService.getActivity(1L)).isInstanceOf(PermissionDeniedException.class);
    }

    private FileEntity fileAt(String filename) {
        try {
            Path path = tempDir.resolve(filename);
            Files.writeString(path, "contenido de prueba");
            return FileEntity.builder()
                    .id(1L)
                    .workspaceId(5L)
                    .name(filename)
                    .type(FileType.FILE)
                    .location(path.toString())
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
