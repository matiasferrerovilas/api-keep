package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.FileDTO;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
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
import org.springframework.data.domain.Pageable;
import org.springframework.util.unit.DataSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the favorite toggle, the favorites listing, and the "recently accessed" listing —
 * including that downloadFile is what actually stamps lastAccessedAt (not tree listing), so
 * "Recientes" only ever shows genuinely opened files.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FavoritesAndRecentTest {

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
    void setFavorite_marksAFileAsFavorite() {
        FileEntity file = fileAt(1L, "doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileDTO result = fileService.setFavorite(1L, true);

        assertThat(file.isFavorite()).isTrue();
        assertThat(result.metadata().favorite()).isTrue();
        verify(fileRepository).save(file);
    }

    @Test
    void setFavorite_unmarksAFavoriteFile() {
        FileEntity file = fileAt(1L, "doc.txt");
        file.setFavorite(true);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        FileDTO result = fileService.setFavorite(1L, false);

        assertThat(file.isFavorite()).isFalse();
        assertThat(result.metadata().favorite()).isFalse();
    }

    @Test
    void setFavorite_throwsWhenTheNodeDoesNotExist() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.setFavorite(99L, true)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listFavorites_returnsOnlyFavoritedFiles() {
        FileEntity favorite = fileAt(1L, "importante.txt");
        favorite.setFavorite(true);
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndFavoriteTrue(5L)).thenReturn(List.of(favorite));

        List<FileDTO> result = fileService.listFavorites(5L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo("1");
        assertThat(result.getFirst().metadata().favorite()).isTrue();
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
    }

    @Test
    void downloadFile_stampsLastAccessedAtOnActualDownload() {
        FileEntity file = fileAt(1L, "doc.txt");
        assertThat(file.getLastAccessedAt()).isNull();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        fileService.downloadFile(1L);

        assertThat(file.getLastAccessedAt()).isNotNull();
        verify(fileRepository).save(file);
    }

    @Test
    void listRecent_ordersByMostRecentlyAccessedFirst() {
        FileEntity recent = fileAt(1L, "nuevo.txt");
        recent.setLastAccessedAt(LocalDateTime.now());
        FileEntity older = fileAt(2L, "viejo.txt");
        older.setLastAccessedAt(LocalDateTime.now().minusDays(1));
        // El orden real (desc por lastAccessedAt) lo garantiza la query derivada de Spring Data;
        // acá se confirma que el service delega en ella y mapea el resultado tal cual viene.
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(
                eq(5L), any(Pageable.class))).thenReturn(List.of(recent, older));

        List<FileDTO> result = fileService.listRecent(5L, 20);

        assertThat(result).extracting(FileDTO::id).containsExactly("1", "2");
    }

    @Test
    void listRecent_excludesFilesNeverAccessed() {
        // findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc
        // is the contract that excludes null lastAccessedAt — a never-opened file simply never
        // reaches the service because the repository already filters it out.
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(
                eq(5L), any(Pageable.class))).thenReturn(List.of());

        List<FileDTO> result = fileService.listRecent(5L, 20);

        assertThat(result).isEmpty();
    }

    @Test
    void listRecent_usesDefaultLimitWhenNoneProvided() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(
                anyLong(), pageableCaptor.capture())).thenReturn(List.of());

        fileService.listRecent(5L, null);

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    private FileEntity fileAt(Long id, String filename) {
        try {
            Path path = tempDir.resolve(filename);
            if (!Files.exists(path)) {
                Files.writeString(path, "contenido de prueba");
            }
            return FileEntity.builder()
                    .id(id)
                    .workspaceId(5L)
                    .parentId(2L)
                    .name(filename)
                    .type(FileType.FILE)
                    .location(path.toString())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
