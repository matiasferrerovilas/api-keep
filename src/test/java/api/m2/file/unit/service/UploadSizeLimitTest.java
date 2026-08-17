package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.mappers.FileDTOMapper;
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
import static org.mockito.Mockito.when;

/**
 * El límite de tamaño de subida solía estar hardcodeado a 50MB en FileService mientras
 * application.yaml permitía hasta 500MB a nivel servlet — un upload de, digamos, 200MB pasaba el
 * servlet entero antes de ser rechazado acá. Ahora ambos vienen de la misma property
 * (app.storage.max-file-size); este test usa un límite chico a propósito para probar la
 * validación sin tener que armar un archivo real de 50MB.
 * Lenient porque el test de "rechaza" nunca llega a usar la mayoría de los stubs de setUp() —
 * la validación de tamaño corta la ejecución en la primera línea de uploadFile.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UploadSizeLimitTest {

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
        StorageProperties storageProperties = new StorageProperties(tempDir.toString(), DataSize.ofBytes(10));
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
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndChecksum(anyLong(), anyString())).thenReturn(Optional.empty());
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(invocation -> {
            FileEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
    }

    @Test
    void uploadFile_rejectsFilesLargerThanTheConfiguredLimit() {
        var tooLarge = new MockMultipartFile("file", "grande.txt", "text/plain", "esto pesa mas de 10 bytes".getBytes());

        assertThatThrownBy(() -> fileService.uploadFile(5L, null, tooLarge))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tamaño máximo permitido");
    }

    @Test
    void uploadFile_allowsFilesAtOrUnderTheConfiguredLimit() {
        // Exactamente 10 bytes: el límite es inclusivo (rechaza estrictamente más grande, no
        // igual), así que esto tiene que subir sin problema — sirve para probar que el chequeo
        // no está apagando uploads legítimos por un off-by-one.
        var atLimit = new MockMultipartFile("file", "justo.txt", "text/plain", "1234567890".getBytes());

        var result = fileService.uploadFile(5L, null, atLimit);

        assertThat(result.name()).isEqualTo("Justo.txt");
    }
}
