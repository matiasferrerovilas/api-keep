package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.FileDTO;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.FileService;
import api.m2.file.service.SourceAppResolver;
import api.m2.file.service.UserService;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

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
 * FileService already computed the SHA-256 checksum on every upload but never did anything with
 * it — these cover the new dedup check: a second upload with identical content anywhere in the
 * same workspace is rejected instead of silently doubling disk usage.
 */
@ExtendWith(MockitoExtension.class)
class UploadDedupTest {

    @Mock
    FileRepository fileRepository;
    @Mock
    AppFileShareRepository appFileShareRepository;
    @Mock
    UserService userService;
    @Mock
    WorkspaceService workspaceService;
    @Mock
    FileDTOMapper fileDTOMapper;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @TempDir
    Path tempDir;

    FileService fileService;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(tempDir.toString());
        fileService = new FileService(
                fileRepository,
                appFileShareRepository,
                storageProperties,
                fileDTOMapper,
                userService,
                workspaceService,
                new SourceAppResolver(),
                eventPublisher);
        when(userService.getMe()).thenReturn(new UserMe(1L, "user@example.com", "Nombre", "Apellido", "PERSONAL", null));
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());

        FileEntity root = FileEntity.builder().id(2L).workspaceId(5L).name("Home").type(FileType.FOLDER)
                .location(tempDir.toString()).build();
        when(fileRepository.findByWorkspaceIdAndParentIdIsNull(5L)).thenReturn(Optional.of(root));
    }

    @Test
    void uploadFile_rejectsWhenIdenticalContentAlreadyExistsInWorkspace() {
        FileEntity existing = FileEntity.builder().id(10L).workspaceId(5L).name("Original.txt").type(FileType.FILE).build();
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndChecksum(anyLong(), anyString()))
                .thenReturn(Optional.of(existing));
        var multipartFile = new MockMultipartFile("file", "copia.txt", "text/plain", "mismo contenido".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(5L, null, multipartFile))
                .isInstanceOf(EntityAlreadyExistsException.class)
                .hasMessageContaining("Original.txt");

        verify(fileRepository, never()).save(any(FileEntity.class));
        // The dedup check runs after the file is written (checksum needs the full content) — it
        // must clean up the just-written duplicate rather than leaving an orphaned copy on disk.
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void uploadFile_rejectsWhenAFileWithTheSameNameAlreadyExists() throws Exception {
        Files.writeString(tempDir.resolve("Existente.txt"), "ya estaba acá");
        var multipartFile = new MockMultipartFile("file", "existente.txt", "text/plain", "contenido nuevo".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(5L, null, multipartFile))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Existente.txt");

        verify(fileRepository, never()).save(any(FileEntity.class));
        assertThat(Files.readString(tempDir.resolve("Existente.txt"))).isEqualTo("ya estaba acá");
    }

    @Test
    void uploadFile_succeedsWhenChecksumIsUnique() {
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndChecksum(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
        var multipartFile = new MockMultipartFile("file", "nuevo.txt", "text/plain", "contenido único".getBytes());

        FileDTO result = fileService.uploadFile(5L, null, multipartFile);

        assertThat(result.name()).isEqualTo("Nuevo.txt");
        verify(fileRepository).save(any(FileEntity.class));
    }
}
