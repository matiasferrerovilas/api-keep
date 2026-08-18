package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.EventType;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.enums.FileType;
import api.m2.file.record.FileDTO;
import api.m2.file.record.events.FileTreeChangedEvent;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.FileService;
import api.m2.file.service.SourceAppResolver;
import api.m2.file.service.UserService;
import api.m2.file.service.storage.LocalFsStorageAdapter;
import api.m2.file.service.workspace.WorkspaceService;
import org.springframework.util.unit.DataSize;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the trash workflow: deleteNode soft-deletes (cascading to a folder's descendants)
 * instead of touching disk, restoreNode undoes that, and purgeExpiredTrash is what actually
 * removes anything past the retention window.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrashTest {

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
        StorageProperties storageProperties = new StorageProperties(tempDir.toString(), DataSize.ofMegabytes(50), DataSize.ofGigabytes(5));
        fileService = new FileService(
                fileRepository,
                appFileShareRepository,
                storageProperties,
                new LocalFsStorageAdapter(),
                fileDTOMapper,
                userService,
                workspaceService,
                new SourceAppResolver(),
                eventPublisher);
        when(userService.getMe()).thenReturn(new UserMe(1L, "user@example.com", "Nombre", "Apellido", "PERSONAL", null));
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());
    }

    @Test
    void deleteNode_softDeletesWithoutTouchingDisk() throws IOException {
        FileEntity file = fileAt(1L, "doc.txt", 2L);
        Files.writeString(Path.of(file.getLocation()), "contenido");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(List.of());

        fileService.deleteNode(1L);

        assertThat(file.getDeletedAt()).isNotNull();
        assertThat(Files.exists(Path.of(file.getLocation()))).isTrue();
        verify(fileRepository).saveAll(List.of(file));
        verifyPublishedEvent(EventType.FILE_DELETED);
    }

    @Test
    void deleteNode_cascadesToDescendantsOfAFolder() {
        FileEntity folder = folderAt(1L, "Fotos", 2L);
        FileEntity child = fileAt(2L, "a.txt", 1L);
        FileEntity grandchild = fileAt(3L, "b.txt", 1L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(List.of(child, grandchild));

        fileService.deleteNode(1L);

        assertThat(folder.getDeletedAt()).isNotNull();
        assertThat(child.getDeletedAt()).isNotNull();
        assertThat(grandchild.getDeletedAt()).isNotNull();
    }

    @Test
    void deleteNode_throwsWhenAlreadyInTrash() {
        FileEntity file = fileAt(1L, "doc.txt", 2L);
        file.setDeletedAt(LocalDateTime.now());
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.deleteNode(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void restoreNode_clearsDeletedAtAndCascadesToDescendants() {
        LocalDateTime deletedAt = LocalDateTime.now().minusHours(2);
        FileEntity folder = folderAt(1L, "Fotos", 2L);
        folder.setDeletedAt(deletedAt);
        FileEntity child = fileAt(2L, "a.txt", 1L);
        child.setDeletedAt(deletedAt);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(5L)).thenReturn(List.of(child));

        fileService.restoreNode(1L);

        assertThat(folder.getDeletedAt()).isNull();
        assertThat(child.getDeletedAt()).isNull();
        verifyPublishedEvent(EventType.FILE_ADDED);
    }

    @Test
    void restoreNode_throwsWhenNotInTrash() {
        FileEntity file = fileAt(1L, "doc.txt", 2L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.restoreNode(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void restoreNode_throwsWhenTheNodeDoesNotExist() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.restoreNode(99L)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listTrash_returnsOnlyDeletedItems() {
        FileEntity trashed = fileAt(1L, "doc.txt", 2L);
        trashed.setDeletedAt(LocalDateTime.now());
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(5L)).thenReturn(List.of(trashed));

        List<FileDTO> result = fileService.listTrash(5L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo("1");
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
    }

    @Test
    void purgeExpiredTrash_removesFileFromDiskAndDatabase() throws IOException {
        FileEntity expired = fileAt(1L, "old.txt", 2L);
        Files.writeString(Path.of(expired.getLocation()), "contenido");
        when(fileRepository.findByDeletedAtBefore(any())).thenReturn(List.of(expired));

        fileService.purgeExpiredTrash();

        assertThat(Files.exists(Path.of(expired.getLocation()))).isFalse();
        verify(fileRepository).delete(expired);
    }

    @Test
    void purgeExpiredTrash_deletesDeeperPathsBeforeShallowerOnes() throws IOException {
        Path parentDir = tempDir.resolve("Carpeta");
        Files.createDirectory(parentDir);
        Path childFile = parentDir.resolve("archivo.txt");
        Files.writeString(childFile, "contenido");

        FileEntity folder = FileEntity.builder()
                .id(1L).workspaceId(5L).parentId(2L).name("Carpeta").type(FileType.FOLDER)
                .location(parentDir.toString()).build();
        FileEntity file = FileEntity.builder()
                .id(2L).workspaceId(5L).parentId(1L).name("archivo.txt").type(FileType.FILE)
                .location(childFile.toString()).build();

        // Deliberately returned parent-first — purgeExpiredTrash must still delete the child
        // first, or Files.deleteIfExists on the (non-empty) folder would silently no-op.
        when(fileRepository.findByDeletedAtBefore(any())).thenReturn(List.of(folder, file));

        fileService.purgeExpiredTrash();

        assertThat(Files.exists(parentDir)).isFalse();
        assertThat(Files.exists(childFile)).isFalse();
    }

    @Test
    void purgeExpiredTrash_doesNothingWhenNothingIsExpired() {
        when(fileRepository.findByDeletedAtBefore(any())).thenReturn(List.of());

        fileService.purgeExpiredTrash();

        verify(fileRepository, never()).delete(any());
    }

    private void verifyPublishedEvent(EventType expectedType) {
        ArgumentCaptor<FileTreeChangedEvent> captor = ArgumentCaptor.forClass(FileTreeChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(expectedType);
    }

    private FileEntity fileAt(Long id, String filename, Long parentId) {
        try {
            Path path = tempDir.resolve(filename);
            if (!Files.exists(path)) {
                Files.writeString(path, "contenido de prueba");
            }
            return FileEntity.builder()
                    .id(id)
                    .workspaceId(5L)
                    .parentId(parentId)
                    .name(filename)
                    .type(FileType.FILE)
                    .location(path.toString())
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private FileEntity folderAt(Long id, String name, Long parentId) {
        return FileEntity.builder()
                .id(id)
                .workspaceId(5L)
                .parentId(parentId)
                .name(name)
                .type(FileType.FOLDER)
                .location(tempDir.resolve(name).toString())
                .build();
    }
}
