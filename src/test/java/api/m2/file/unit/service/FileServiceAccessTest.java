package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.AppFileShare;
import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.mappers.FileDTOMapper;
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
import org.springframework.util.unit.DataSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Covers the access gate added to FileService: native workspace membership is the normal path,
 * and a caller from a different app (resolved from the JWT's app claim) can only reach a file
 * through an explicit AppFileShare grant at the required permission level. Before this, the
 * permission column was stored but nothing ever checked it.
 */
@ExtendWith(MockitoExtension.class)
class FileServiceAccessTest {

    private static final String CALLING_APP = "api-movements";

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
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void downloadFile_allowsNativeWorkspaceMembers() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);

        var result = fileService.downloadFile(1L);

        assertThat(result.filename()).isEqualTo("doc.txt");
    }

    @Test
    void downloadFile_deniesNonMembersWithoutAnyShare() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(appFileShareRepository.findByFileIdAndApiName(1L, CALLING_APP)).thenReturn(Optional.empty());
        authenticateAsClient(CALLING_APP);

        assertThatThrownBy(() -> fileService.downloadFile(1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void downloadFile_deniesReadWhenShareOnlyGrantsWrite() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(appFileShareRepository.findByFileIdAndApiName(1L, CALLING_APP))
                .thenReturn(Optional.of(shareWith(SharePermission.WRITE)));
        authenticateAsClient(CALLING_APP);

        assertThatThrownBy(() -> fileService.downloadFile(1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void downloadFile_allowsReadWhenSharedWithReadPermission() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(appFileShareRepository.findByFileIdAndApiName(1L, CALLING_APP))
                .thenReturn(Optional.of(shareWith(SharePermission.READ)));
        authenticateAsClient(CALLING_APP);

        var result = fileService.downloadFile(1L);

        assertThat(result.filename()).isEqualTo("doc.txt");
    }

    @Test
    void deleteNode_deniesWriteWhenShareOnlyGrantsRead() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(appFileShareRepository.findByFileIdAndApiName(1L, CALLING_APP))
                .thenReturn(Optional.of(shareWith(SharePermission.READ)));
        authenticateAsClient(CALLING_APP);

        assertThatThrownBy(() -> fileService.deleteNode(1L))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(Files.exists(Path.of(file.getLocation()))).isTrue();
    }

    @Test
    void deleteNode_allowsWriteWhenSharedWithReadWritePermission() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(appFileShareRepository.findByFileIdAndApiName(1L, CALLING_APP))
                .thenReturn(Optional.of(shareWith(SharePermission.READ_WRITE)));
        authenticateAsClient(CALLING_APP);

        fileService.deleteNode(1L);

        // Soft-delete: the file is trashed (marked, hidden from the tree) but stays on disk until
        // the retention window passes.
        assertThat(file.getDeletedAt()).isNotNull();
        assertThat(Files.exists(Path.of(file.getLocation()))).isTrue();
    }

    @Test
    void downloadFile_allowsReadWhenSharedDirectlyWithTheUser() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L))
                .thenReturn(Optional.of(userShareWith(1L, SharePermission.READ, null)));

        var result = fileService.downloadFile(1L);

        assertThat(result.filename()).isEqualTo("doc.txt");
    }

    @Test
    void downloadFile_deniesReadWhenTheUserShareOnlyGrantsWrite() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L))
                .thenReturn(Optional.of(userShareWith(1L, SharePermission.WRITE, null)));

        assertThatThrownBy(() -> fileService.downloadFile(1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void downloadFile_deniesReadWhenTheUserShareHasAlreadyExpired() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L))
                .thenReturn(Optional.of(userShareWith(1L, SharePermission.READ, LocalDateTime.now().minusDays(1))));

        assertThatThrownBy(() -> fileService.downloadFile(1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void downloadFile_allowsReadWhenTheUserShareExpiresInTheFuture() {
        FileEntity file = fileAt("doc.txt");
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L))
                .thenReturn(Optional.of(userShareWith(1L, SharePermission.READ, LocalDateTime.now().plusDays(1))));

        var result = fileService.downloadFile(1L);

        assertThat(result.filename()).isEqualTo("doc.txt");
    }

    @Test
    void downloadFile_cascadesAccessFromASharedAncestorFolderToAFileInsideIt() {
        // file (id=1) has no share of its own; its parent folder (id=2) does — access must walk
        // up from the file to find it. This is the mechanism that lets sharing a folder cover
        // everything inside it, including files added to it after the share was created.
        FileEntity file = fileAt("doc.txt"); // parentId = 2
        FileEntity parentFolder = FileEntity.builder()
                .id(2L).workspaceId(5L).parentId(null).name("Carpeta").type(FileType.FOLDER).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(2L)).thenReturn(Optional.of(parentFolder));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L)).thenReturn(Optional.empty());
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(2L, 1L))
                .thenReturn(Optional.of(userShareWith(2L, SharePermission.READ, null)));

        var result = fileService.downloadFile(1L);

        assertThat(result.filename()).isEqualTo("doc.txt");
    }

    @Test
    void downloadFile_deniesAccessWhenNeitherTheFileNorAnyAncestorFolderIsShared() {
        FileEntity file = fileAt("doc.txt"); // parentId = 2
        FileEntity parentFolder = FileEntity.builder()
                .id(2L).workspaceId(5L).parentId(null).name("Carpeta").type(FileType.FOLDER).build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileRepository.findById(2L)).thenReturn(Optional.of(parentFolder));
        denyNativeMembership();
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(1L, 1L)).thenReturn(Optional.empty());
        when(userFileShareRepository.findByFileIdAndSharedWithUserId(2L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.downloadFile(1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    private UserFileShare userShareWith(Long fileId, SharePermission permission, LocalDateTime expiresAt) {
        return UserFileShare.builder()
                .id(9L).fileId(fileId).sharedWithUserId(1L).sharedWithEmail("user@example.com")
                .permission(permission).expiresAt(expiresAt).createdBy(2L).build();
    }

    private FileEntity fileAt(String filename) {
        try {
            Path path = tempDir.resolve(filename);
            Files.writeString(path, "contenido de prueba");
            return FileEntity.builder()
                    .id(1L)
                    .workspaceId(5L)
                    .parentId(2L)
                    .name(filename)
                    .type(FileType.FILE)
                    .location(path.toString())
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void denyNativeMembership() {
        doThrow(new PermissionDeniedException("No tienes permiso para operar sobre este recurso"))
                .when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());
    }

    private AppFileShare shareWith(SharePermission permission) {
        return AppFileShare.builder().id(9L).fileId(1L).apiName(CALLING_APP).permission(permission).createdBy(1L).build();
    }

    private static void authenticateAsClient(String app) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("app", app)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
