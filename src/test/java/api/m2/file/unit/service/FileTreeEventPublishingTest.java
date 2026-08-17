package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.EventType;
import api.m2.file.enums.FileType;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.events.FileTreeChangedEvent;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.FileService;
import api.m2.file.service.SourceAppResolver;
import api.m2.file.service.UserService;
import api.m2.file.service.workspace.WorkspaceService;
import org.springframework.util.unit.DataSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileService used to mutate the tree silently — WebSocketConfig exposed /ws but nothing ever
 * called messagingTemplate.convertAndSend. These tests confirm each mutation now raises a
 * FileTreeChangedEvent (consumed by FileTreePublishServiceWebSocket) with the right workspace and
 * event type.
 */
@ExtendWith(MockitoExtension.class)
class FileTreeEventPublishingTest {

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
        StorageProperties storageProperties = new StorageProperties(tempDir.toString(), DataSize.ofMegabytes(50));
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
    }

    @Test
    void uploadFile_publishesFileAdded() {
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

        FileTreeChangedEvent event = captureEvent();
        assertThat(event.workspaceId()).isEqualTo(5L);
        assertThat(event.eventType()).isEqualTo(EventType.FILE_ADDED);
        assertThat(event.file().name()).isEqualTo("Recibo.txt");
    }

    @Test
    void renameNode_publishesFileUpdated() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.renameNode(1L, "renombrado.txt");

        FileTreeChangedEvent event = captureEvent();
        assertThat(event.workspaceId()).isEqualTo(5L);
        assertThat(event.eventType()).isEqualTo(EventType.FILE_UPDATED);
        assertThat(event.file().name()).isEqualTo("renombrado.txt");
    }

    @Test
    void deleteNode_publishesFileDeletedWithoutFullMetadata() {
        FileEntity file = fileAt("doc.txt");
        file.setParentId(2L);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.deleteNode(1L);

        FileTreeChangedEvent event = captureEvent();
        assertThat(event.workspaceId()).isEqualTo(5L);
        assertThat(event.eventType()).isEqualTo(EventType.FILE_DELETED);
        assertThat(event.file().id()).isEqualTo("1");
    }

    private FileTreeChangedEvent captureEvent() {
        ArgumentCaptor<FileTreeChangedEvent> captor = ArgumentCaptor.forClass(FileTreeChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
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
