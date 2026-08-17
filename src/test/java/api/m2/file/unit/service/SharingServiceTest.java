package api.m2.file.unit.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.entity.AppFileShare;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.AppFileShareMapper;
import api.m2.file.record.CreateFileShareRequest;
import api.m2.file.record.FileShareResponse;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.FileShareEventPublisher;
import api.m2.file.service.SharingService;
import api.m2.file.service.UserService;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharingServiceTest {

    @Mock
    AppFileShareRepository fileShareRepository;
    @Mock
    FileRepository fileRepository;
    @Mock
    UserService userService;
    @Mock
    WorkspaceService workspaceService;
    @Mock
    AppFileShareMapper appFileShareMapper;
    @Mock
    FileShareEventPublisher fileShareEventPublisher;

    SharingService sharingService;

    FileEntity file = FileEntity.builder().id(1L).workspaceId(5L).name("doc.txt").type(FileType.FILE).build();
    UserMe owner = new UserMe(1L, "owner@example.com", "Nombre", "Apellido", "PERSONAL", null);

    @BeforeEach
    void setUp() {
        sharingService = new SharingService(
                fileShareRepository, fileRepository, userService, workspaceService, appFileShareMapper, fileShareEventPublisher);
        when(userService.getMe()).thenReturn(owner);
    }

    @Test
    void shareFile_savesTheGrantAfterVerifyingMembership() {
        var request = new CreateFileShareRequest(1L, "api-movements", SharePermission.READ);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileShareRepository.existsByFileIdAndApiName(1L, "api-movements")).thenReturn(false);
        when(appFileShareMapper.toResponse(any(AppFileShare.class))).thenAnswer(invocation -> {
            AppFileShare share = invocation.getArgument(0);
            return FileShareResponse.builder().id(share.getId()).fileId(share.getFileId())
                    .apiName(share.getApiName()).permission(share.getPermission()).build();
        });

        FileShareResponse response = sharingService.shareFile(request);

        assertThat(response.apiName()).isEqualTo("api-movements");
        assertThat(response.permission()).isEqualTo(SharePermission.READ);
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
        verify(fileShareEventPublisher).publishFileShared(argThat(event ->
                event.fileId().equals(1L) && event.apiName().equals("api-movements") && event.permission() == SharePermission.READ));
        verify(fileShareRepository, times(1)).save(any(AppFileShare.class));
    }

    @Test
    void shareFile_rejectsADuplicateGrantForTheSameApi() {
        var request = new CreateFileShareRequest(1L, "api-movements", SharePermission.READ);
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(fileShareRepository.existsByFileIdAndApiName(1L, "api-movements")).thenReturn(true);

        assertThatThrownBy(() -> sharingService.shareFile(request))
                .isInstanceOf(EntityAlreadyExistsException.class);
        verify(fileShareRepository, never()).save(any());
    }

    @Test
    void shareFile_throwsWhenTheFileDoesNotExist() {
        var request = new CreateFileShareRequest(404L, "api-movements", SharePermission.READ);
        when(fileRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sharingService.shareFile(request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getShares_listsGrantsAfterVerifyingMembership() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        var share = AppFileShare.builder().id(9L).fileId(1L).apiName("api-movements")
                .permission(SharePermission.READ).createdBy(1L).build();
        when(fileShareRepository.findByFileId(1L)).thenReturn(List.of(share));
        when(appFileShareMapper.toResponse(share)).thenReturn(
                FileShareResponse.builder().id(9L).fileId(1L).apiName("api-movements").permission(SharePermission.READ).build());

        List<FileShareResponse> shares = sharingService.getShares(1L);

        assertThat(shares).hasSize(1);
        assertThat(shares.getFirst().apiName()).isEqualTo("api-movements");
        verify(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
    }
}
