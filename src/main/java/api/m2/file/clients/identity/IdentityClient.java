package api.m2.file.clients.identity;

import api.m2.file.clients.identity.requests.AcceptRejectInvitationDTO;
import api.m2.file.clients.identity.requests.AddWorkspaceRecord;
import api.m2.file.clients.identity.requests.OnboardingStartRequest;
import api.m2.file.clients.identity.requests.UserToAdd;
import api.m2.file.clients.identity.requests.WorkspaceSendInvitationDTO;
import api.m2.file.clients.identity.response.OnboardingStartResponse;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.clients.identity.response.WorkspaceAdded;
import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceMemberDTO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

import java.util.List;

@HttpExchange
public interface IdentityClient {

    @PostExchange("/v1/users")
    UserMe createLogInUser(@RequestBody UserToAdd user);

    @PostExchange("/v1/onboarding/start")
    OnboardingStartResponse startOnboarding(@RequestBody OnboardingStartRequest request);

    @PatchExchange("/v1/onboarding/{userId}/first-login")
    void changeUserFirstLoginStatus(@PathVariable Long userId);

    @GetExchange("/v1/users/me")
    UserMe getMe();

    @PostExchange("/v1/workspaces")
    List<WorkspaceAdded> createWorkspaces(@RequestBody List<AddWorkspaceRecord> workspaces);

    @GetExchange("/v1/workspaces/members")
    List<WorkspaceMemberDTO> getWorkspaces();

    @GetExchange("/v1/invitations")
    List<WorkspaceInvitationDTO> getInvitations();

    @GetExchange("/v1/workspaces/{workspaceId}/members/{userId}")
    void verifyMembership(@PathVariable Long workspaceId, @PathVariable Long userId);

    @DeleteExchange("/v1/workspaces/{workspaceId}")
    void leaveWorkspace(@PathVariable Long workspaceId);

    @DeleteExchange("/v1/workspaces/{workspaceId}/members/{userId}")
    void removeMember(@PathVariable Long workspaceId, @PathVariable Long userId);

    @PutExchange("/v1/onboarding/tour")
    void markTourAsSeen();

    @PostExchange("/v1/invitations/{workspaceId}")
    void sendInvitation(@PathVariable Long workspaceId, @RequestBody WorkspaceSendInvitationDTO body);

    @PatchExchange("/v1/invitations")
    void acceptRejectInvitation(@RequestBody AcceptRejectInvitationDTO body);

}
