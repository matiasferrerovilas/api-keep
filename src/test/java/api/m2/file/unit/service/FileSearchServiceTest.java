package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.FileSearchResult;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers FileService's orchestration of the search endpoint: passing the query through to
 * {@link FileRepository#searchByWorkspaceIdAndQuery} (the indexed, case-insensitive SQL {@code
 * LIKE} against name and, for .txt/.md, extracted content — see FileService's Javadoc on
 * {@code TEXT_SEARCHABLE_EXTENSIONS} for the content-search scope decision), mapping matches into
 * {@link FileSearchResult}, and reconstructing each result's breadcrumb by walking parentId.
 *
 * <p>Like the rest of this suite, the repository is mocked rather than backed by a real database,
 * so each scenario below stubs {@code searchByWorkspaceIdAndQuery} with the result the underlying
 * JPQL ({@code lower(name) like lower(...) or lower(content) like lower(...)}) is expected to
 * produce for that case, and asserts the service surfaces/maps it correctly.
 */
@ExtendWith(MockitoExtension.class)
class FileSearchServiceTest {

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
    void searchFiles_matchesByNameAndBuildsBreadcrumbFromParentChain() {
        FileEntity root = folderAt(1L, "Home", null);
        FileEntity fotos = folderAt(2L, "Fotos", 1L);
        FileEntity match = fileAt(3L, "Vacaciones.txt", 2L, null);

        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "Vacaciones")).thenReturn(List.of(match));
        when(fileRepository.findAllById(Set.of(2L))).thenReturn(List.of(fotos));
        when(fileRepository.findAllById(Set.of(1L))).thenReturn(List.of(root));

        List<FileSearchResult> results = fileService.searchFiles(5L, "Vacaciones");

        assertThat(results).hasSize(1);
        FileSearchResult result = results.getFirst();
        assertThat(result.id()).isEqualTo("3");
        assertThat(result.name()).isEqualTo("Vacaciones.txt");
        assertThat(result.type()).isEqualTo(FileType.FILE);
        assertThat(result.parentId()).isEqualTo("2");
        assertThat(result.path()).containsExactly("Home", "Fotos");
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
    }

    @Test
    void searchFiles_fetchesSharedAncestorOnceInsteadOfLoadingTheWholeTree() {
        // Dos matches bajo la misma carpeta: loadAncestors no debe pedir el ancestro común "Fotos"
        // dos veces, y nunca debe caer al patrón viejo de traer todo el árbol del workspace.
        FileEntity root = folderAt(1L, "Home", null);
        FileEntity fotos = folderAt(2L, "Fotos", 1L);
        FileEntity vacaciones = fileAt(3L, "Vacaciones.txt", 2L, null);
        FileEntity playa = fileAt(4L, "Playa.txt", 2L, null);

        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "20")).thenReturn(List.of(vacaciones, playa));
        when(fileRepository.findAllById(Set.of(2L))).thenReturn(List.of(fotos));
        when(fileRepository.findAllById(Set.of(1L))).thenReturn(List.of(root));

        List<FileSearchResult> results = fileService.searchFiles(5L, "20");

        assertThat(results).extracting(FileSearchResult::path)
                .containsExactly(List.of("Home", "Fotos"), List.of("Home", "Fotos"));
        verify(fileRepository).findAllById(Set.of(2L));
        verify(fileRepository).findAllById(Set.of(1L));
        verify(fileRepository, org.mockito.Mockito.never()).findByWorkspaceIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    void searchFiles_matchesByNameCaseInsensitively() {
        // La query llega en mayúsculas mientras el nombre guardado está en minúsculas — el LIKE
        // case-insensitive del repositorio (lower(name) like lower(query)) es lo que hace que esto
        // matchee; acá se confirma que el service no filtra ese resultado por su cuenta.
        FileEntity match = fileAt(3L, "presupuesto.txt", null, null);
        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "PRESUPUESTO")).thenReturn(List.of(match));

        List<FileSearchResult> results = fileService.searchFiles(5L, "PRESUPUESTO");

        assertThat(results).extracting(FileSearchResult::name).containsExactly("presupuesto.txt");
    }

    @Test
    void searchFiles_matchesTextFileContent() {
        // El nombre no contiene la query, solo el contenido extraído en la subida (.txt/.md) — el
        // repositorio la matchea por el lado "content" del OR.
        FileEntity match = fileAt(3L, "notas.txt", null, "Reunión con el equipo de Contabilidad");
        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "contabilidad")).thenReturn(List.of(match));

        List<FileSearchResult> results = fileService.searchFiles(5L, "contabilidad");

        assertThat(results).extracting(FileSearchResult::name).containsExactly("notas.txt");
    }

    @Test
    void searchFiles_returnsEmptyListWhenNothingMatches() {
        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "inexistente")).thenReturn(List.of());

        List<FileSearchResult> results = fileService.searchFiles(5L, "inexistente");

        assertThat(results).isEmpty();
    }

    @Test
    void searchFiles_nonTextFilesNeverGetContentSearchHits() {
        // Un PDF/imagen nunca tiene `content` poblado (fuera de alcance: requeriría Apache Tika o
        // similar), así que una query que solo matchearía contenido hipotético no lo trae.
        when(fileRepository.searchByWorkspaceIdAndQuery(5L, "cláusula de confidencialidad")).thenReturn(List.of());

        List<FileSearchResult> results = fileService.searchFiles(5L, "cláusula de confidencialidad");

        assertThat(results).isEmpty();
    }

    @Test
    void searchFiles_returnsEmptyListForABlankQueryWithoutHittingTheRepository() {
        List<FileSearchResult> results = fileService.searchFiles(5L, "   ");

        assertThat(results).isEmpty();
    }

    private FileEntity fileAt(Long id, String name, Long parentId, String content) {
        return FileEntity.builder().id(id).workspaceId(5L).parentId(parentId).name(name).type(FileType.FILE)
                .content(content).build();
    }

    private FileEntity folderAt(Long id, String name, Long parentId) {
        return FileEntity.builder().id(id).workspaceId(5L).parentId(parentId).name(name).type(FileType.FOLDER).build();
    }
}
