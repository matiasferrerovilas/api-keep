package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserLookupResponse;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.UserFileShareMapper;
import api.m2.file.record.CreateUserFileShareRequest;
import api.m2.file.record.UpdateUserFileShareRequest;
import api.m2.file.record.UserFileShareResponse;
import api.m2.file.repository.FileRepository;
import api.m2.file.repository.UserFileShareRepository;
import api.m2.file.service.FileActivityLogService;
import api.m2.file.service.UserService;
import api.m2.file.service.UserSharingService;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSharingServiceTest {

    @Mock
    UserFileShareRepository userFileShareRepository;
    @Mock
    FileRepository fileRepository;
    @Mock
    UserService userService;
    @Mock
    WorkspaceService workspaceService;
    @Mock
    UserFileShareMapper userFileShareMapper;
    @Mock
    FileActivityLogService fileActivityLogService;

    UserSharingService userSharingService;

    FileEntity file = FileEntity.builder().id(1L).workspaceId(5L).name("doc.txt").type(FileType.FILE).build();
    UserMe owner = new UserMe(1L, "owner@example.com", "Nombre", "Apellido", "PERSONAL", null);

    @BeforeEach
    void setUp() {
        userSharingService = new UserSharingService(
                userFileShareRepository, fileRepository, userService, workspaceService, userFileShareMapper,
                fileActivityLogService);
        lenient().when(userService.getMe()).thenReturn(owner);
    }

    @Test
    void shareWithUser_savesTheGrantAfterResolvingTheEmail() {
        var request = new CreateUserFileShareRequest(1L, "friend@example.com", SharePermission.READ, null);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userService.lookupUserByEmail("friend@example.com"))
                .thenReturn(new UserLookupResponse(42L, "friend@example.com"));
        when(userFileShareRepository.existsByFileIdAndSharedWithUserId(1L, 42L)).thenReturn(false);
        when(userFileShareMapper.toResponse(any(UserFileShare.class))).thenAnswer(invocation -> {
            UserFileShare share = invocation.getArgument(0);
            return UserFileShareResponse.builder().id(share.getId()).fileId(share.getFileId())
                    .sharedWithUserId(share.getSharedWithUserId()).sharedWithEmail(share.getSharedWithEmail())
                    .permission(share.getPermission()).expiresAt(share.getExpiresAt()).build();
        });

        UserFileShareResponse response = userSharingService.shareWithUser(request);

        assertThat(response.sharedWithUserId()).isEqualTo(42L);
        assertThat(response.sharedWithEmail()).isEqualTo("friend@example.com");
        assertThat(response.permission()).isEqualTo(SharePermission.READ);
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
        verify(userFileShareRepository, times(1)).save(any(UserFileShare.class));
        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.SHARED,
                "doc.txt", 1L, "owner@example.com", "con 'friend@example.com'");
    }

    @Test
    void shareWithUser_rejectsADuplicateGrantForTheSamePerson() {
        var request = new CreateUserFileShareRequest(1L, "friend@example.com", SharePermission.READ, null);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userService.lookupUserByEmail("friend@example.com"))
                .thenReturn(new UserLookupResponse(42L, "friend@example.com"));
        when(userFileShareRepository.existsByFileIdAndSharedWithUserId(1L, 42L)).thenReturn(true);

        assertThatThrownBy(() -> userSharingService.shareWithUser(request))
                .isInstanceOf(EntityAlreadyExistsException.class);
        verify(userFileShareRepository, never()).save(any());
    }

    @Test
    void shareWithUser_rejectsSharingAFileWithYourself() {
        var request = new CreateUserFileShareRequest(1L, "owner@example.com", SharePermission.READ, null);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userService.lookupUserByEmail("owner@example.com"))
                .thenReturn(new UserLookupResponse(1L, "owner@example.com"));

        assertThatThrownBy(() -> userSharingService.shareWithUser(request))
                .isInstanceOf(BusinessException.class);
        verify(userFileShareRepository, never()).save(any());
        verify(userFileShareRepository, never()).existsByFileIdAndSharedWithUserId(any(), any());
    }

    @Test
    void shareWithUser_throwsWhenNoAccountMatchesTheEmail() {
        var request = new CreateUserFileShareRequest(1L, "nobody@example.com", SharePermission.READ, null);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userService.lookupUserByEmail("nobody@example.com"))
                .thenThrow(new EntityNotFoundException("No existe ningún usuario con el email 'nobody@example.com'"));

        assertThatThrownBy(() -> userSharingService.shareWithUser(request))
                .isInstanceOf(EntityNotFoundException.class);
        verify(userFileShareRepository, never()).save(any());
    }

    @Test
    void shareWithUser_throwsWhenTheFileDoesNotExist() {
        var request = new CreateUserFileShareRequest(404L, "friend@example.com", SharePermission.READ, null);
        when(fileRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSharingService.shareWithUser(request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shareWithUser_persistsTheExpirationDateWhenProvided() {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);
        var request = new CreateUserFileShareRequest(1L, "friend@example.com", SharePermission.READ, expiresAt);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userService.lookupUserByEmail("friend@example.com"))
                .thenReturn(new UserLookupResponse(42L, "friend@example.com"));
        when(userFileShareMapper.toResponse(any(UserFileShare.class))).thenAnswer(invocation -> {
            UserFileShare share = invocation.getArgument(0);
            return UserFileShareResponse.builder().expiresAt(share.getExpiresAt()).build();
        });

        UserFileShareResponse response = userSharingService.shareWithUser(request);

        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void updateShare_replacesPermissionAndExpirationInPlace() {
        var share = UserFileShare.builder().id(9L).fileId(1L).sharedWithUserId(42L)
                .sharedWithEmail("friend@example.com").permission(SharePermission.READ).createdBy(1L).build();
        LocalDateTime newExpiry = LocalDateTime.now().plusDays(30);
        var request = new UpdateUserFileShareRequest(SharePermission.READ_WRITE, newExpiry);
        when(userFileShareRepository.findById(9L)).thenReturn(Optional.of(share));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userFileShareMapper.toResponse(share)).thenAnswer(invocation ->
                UserFileShareResponse.builder().id(share.getId()).permission(share.getPermission())
                        .expiresAt(share.getExpiresAt()).build());

        UserFileShareResponse response = userSharingService.updateShare(9L, request);

        assertThat(response.permission()).isEqualTo(SharePermission.READ_WRITE);
        assertThat(response.expiresAt()).isEqualTo(newExpiry);
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
        verify(userFileShareRepository, times(1)).save(share);
        verify(userFileShareRepository, never()).delete(any());
    }

    @Test
    void updateShare_clearsExpirationWhenSentAsNull() {
        var share = UserFileShare.builder().id(9L).fileId(1L).sharedWithUserId(42L)
                .sharedWithEmail("friend@example.com").permission(SharePermission.READ)
                .expiresAt(LocalDateTime.now().plusDays(1)).createdBy(1L).build();
        var request = new UpdateUserFileShareRequest(SharePermission.READ, null);
        when(userFileShareRepository.findById(9L)).thenReturn(Optional.of(share));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(userFileShareMapper.toResponse(share)).thenReturn(UserFileShareResponse.builder().build());

        userSharingService.updateShare(9L, request);

        assertThat(share.getExpiresAt()).isNull();
    }

    @Test
    void updateShare_throwsWhenTheShareDoesNotExist() {
        when(userFileShareRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSharingService.updateShare(404L, new UpdateUserFileShareRequest(SharePermission.READ, null)))
                .isInstanceOf(EntityNotFoundException.class);
        verify(userFileShareRepository, never()).save(any());
    }

    @Test
    void getShares_listsGrantsAfterVerifyingMembership() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        var share = UserFileShare.builder().id(9L).fileId(1L).sharedWithUserId(42L)
                .sharedWithEmail("friend@example.com").permission(SharePermission.READ).createdBy(1L).build();
        when(userFileShareRepository.findByFileId(1L)).thenReturn(List.of(share));
        when(userFileShareMapper.toResponse(share)).thenReturn(
                UserFileShareResponse.builder().id(9L).fileId(1L).sharedWithEmail("friend@example.com")
                        .permission(SharePermission.READ).build());

        List<UserFileShareResponse> shares = userSharingService.getShares(1L);

        assertThat(shares).hasSize(1);
        assertThat(shares.getFirst().sharedWithEmail()).isEqualTo("friend@example.com");
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
    }

    @Test
    void revokeShare_deletesTheGrantAfterVerifyingMembership() {
        var share = UserFileShare.builder().id(9L).fileId(1L).sharedWithUserId(42L)
                .sharedWithEmail("friend@example.com").permission(SharePermission.READ_WRITE).createdBy(1L).build();
        when(userFileShareRepository.findById(9L)).thenReturn(Optional.of(share));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        userSharingService.revokeShare(9L);

        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
        verify(userFileShareRepository, times(1)).delete(share);
        verify(fileActivityLogService).record(1L, 5L, FileActivityAction.UNSHARED,
                "doc.txt", 1L, "owner@example.com", "con 'friend@example.com'");
    }

    @Test
    void revokeShare_throwsWhenTheShareDoesNotExist() {
        when(userFileShareRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSharingService.revokeShare(404L))
                .isInstanceOf(EntityNotFoundException.class);
        verify(userFileShareRepository, never()).delete(any());
    }
}
