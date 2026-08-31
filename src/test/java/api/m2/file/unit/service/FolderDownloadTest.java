package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileActivityRepository;
import api.m2.file.mappers.FileActivityMapper;
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
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/** Covers the zip-download fix: the disk still holds a trashed file's bytes until it's purged,
 * but a zip of the folder above it should reflect the DB (deletedAt), not the disk. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FolderDownloadTest {

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
    void downloadFile_zippingAFolderExcludesFilesAlreadyInTheTrash() throws IOException {
        Path folderPath = tempDir.resolve("Carpeta");
        Files.createDirectory(folderPath);
        Path keptPath = folderPath.resolve("kept.txt");
        Files.writeString(keptPath, "vivo");
        Path trashedPath = folderPath.resolve("trashed.txt");
        // Todavía en disco: el barrido de purga recién lo borra a las 24hs.
        Files.writeString(trashedPath, "en la papelera");

        FileEntity folder = FileEntity.builder()
                .id(1L).workspaceId(5L).parentId(99L).name("Carpeta").type(FileType.FOLDER)
                .location(folderPath.toString()).build();
        FileEntity kept = FileEntity.builder()
                .id(2L).workspaceId(5L).parentId(1L).name("kept.txt").type(FileType.FILE)
                .location(keptPath.toString()).build();
        FileEntity trashed = FileEntity.builder()
                .id(3L).workspaceId(5L).parentId(1L).name("trashed.txt").type(FileType.FILE)
                .location(trashedPath.toString()).deletedAt(LocalDateTime.now()).build();

        when(fileRepository.findById(1L)).thenReturn(Optional.of(folder));
        // findByWorkspaceIdAndDeletedAtIsNull ya excluye lo trasheado, tal como hace getPersonalFolder.
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNull(5L)).thenReturn(List.of(folder, kept));

        var result = fileService.downloadFile(1L);

        List<String> zipEntries = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        result.body().writeTo(out);
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zipEntries.add(entry.getName());
            }
        }

        assertThat(zipEntries).containsExactly("kept.txt");
    }
}
