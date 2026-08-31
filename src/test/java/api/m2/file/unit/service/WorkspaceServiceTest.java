package api.m2.file.unit.service;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.clients.identity.requests.AcceptRejectInvitationDTO;
import api.m2.file.clients.identity.requests.WorkspaceSendInvitationDTO;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceSentInvitationDTO;
import api.m2.file.enums.InvitationStatus;
import api.m2.file.enums.UserSettingKey;
import api.m2.file.enums.WorkspaceRole;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.service.UserService;
import api.m2.file.service.settings.UserSettingService;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    IdentityClient identityClient;
    @Mock
    UserService userService;
    @Mock
    UserSettingService userSettingService;

    WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(identityClient, userService, userSettingService);
    }

    @Test
    void leaveWorkspace_delegatesToIdentityClient() {
        when(userService.getMe()).thenReturn(new UserMe(1L, "a@test.com", "A", "A", "PERSONAL", null));
        when(userSettingService.getDefaultWorkspaceId(1L)).thenReturn(Optional.of(10L));

        workspaceService.leaveWorkspace(10L);

        verify(identityClient).leaveWorkspace(10L);
        verify(userSettingService).deleteByKey(UserSettingKey.DEFAULT_WORKSPACE);
    }

    @Test
    void leaveWorkspace_doesNotClearDefaultWhenLeavingADifferentWorkspace() {
        when(userService.getMe()).thenReturn(new UserMe(1L, "a@test.com", "A", "A", "PERSONAL", null));
        when(userSettingService.getDefaultWorkspaceId(1L)).thenReturn(Optional.of(99L));

        workspaceService.leaveWorkspace(10L);

        verify(userSettingService, never()).deleteByKey(any());
    }

    @Test
    void removeMember_delegatesToIdentityClientAndClearsTargetsDefaultIfMatching() {
        when(userSettingService.getDefaultWorkspaceId(2L)).thenReturn(Optional.of(10L));

        workspaceService.removeMember(10L, 2L);

        verify(identityClient).removeMember(10L, 2L);
        verify(userSettingService).deleteByKeyForUser(2L, UserSettingKey.DEFAULT_WORKSPACE);
    }

    @Test
    void removeMember_doesNotClearTargetsDefaultWhenItPointsElsewhere() {
        when(userSettingService.getDefaultWorkspaceId(2L)).thenReturn(Optional.of(99L));

        workspaceService.removeMember(10L, 2L);

        verify(userSettingService, never()).deleteByKeyForUser(eq(2L), any());
    }

    @Test
    void verifyUserIsMemberOfWorkspace_delegatesToIdentityClient() {
        doNothing().when(identityClient).verifyMembership(10L, 1L);

        workspaceService.verifyUserIsMemberOfWorkspace(10L, 1L);

        verify(identityClient).verifyMembership(10L, 1L);
    }

    @Test
    void verifyUserIsMemberOfWorkspace_translatesIdentityRejectionIntoPermissionDenied() {
        doThrow(new RestClientResponseException("Forbidden", 403, "Forbidden", null, null, null))
                .when(identityClient).verifyMembership(10L, 1L);

        assertThatThrownBy(() -> workspaceService.verifyUserIsMemberOfWorkspace(10L, 1L))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void getMyInvitations_returnsWhatIdentityClientReturns() {
        var invitation = new WorkspaceInvitationDTO(1L, 10L, "Familia", "owner@test.com",
                InvitationStatus.PENDING, WorkspaceRole.COLLABORATOR, LocalDateTime.now());
        when(identityClient.getInvitations()).thenReturn(List.of(invitation));

        var result = workspaceService.getMyInvitations();

        assertThat(result).containsExactly(invitation);
    }

    @Test
    void sendInvitation_delegatesToIdentityClient() {
        var body = new WorkspaceSendInvitationDTO(10L, List.of("nuevo@test.com"), WorkspaceRole.COLLABORATOR);

        workspaceService.sendInvitation(10L, body);

        verify(identityClient).sendInvitation(10L, body);
    }

    @Test
    void acceptRejectInvitation_delegatesToIdentityClient() {
        var body = new AcceptRejectInvitationDTO(1L, true);

        workspaceService.acceptRejectInvitation(body);

        verify(identityClient).acceptRejectInvitation(body);
    }

    @Test
    void getSentInvitations_returnsWhatIdentityClientReturns() {
        var invitation = new WorkspaceSentInvitationDTO(1L, 10L, "Familia", "invited@test.com",
                InvitationStatus.PENDING, WorkspaceRole.COLLABORATOR, LocalDateTime.now());
        when(identityClient.getSentInvitations()).thenReturn(List.of(invitation));

        var result = workspaceService.getSentInvitations();

        assertThat(result).containsExactly(invitation);
    }

    @Test
    void cancelInvitation_delegatesToIdentityClient() {
        workspaceService.cancelInvitation(5L);

        verify(identityClient).cancelInvitation(5L);
    }
}
