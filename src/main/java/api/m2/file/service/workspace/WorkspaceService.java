package api.m2.file.service.workspace;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.clients.identity.requests.AcceptRejectInvitationDTO;
import api.m2.file.clients.identity.requests.AddWorkspaceRecord;
import api.m2.file.clients.identity.requests.WorkspaceSendInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceSentInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceMemberDTO;
import api.m2.file.configuration.CacheConfiguration;
import api.m2.file.enums.UserSettingKey;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.service.UserService;
import api.m2.file.service.settings.UserSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceService {
    private final IdentityClient identityClient;
    private final UserService userService;
    private final UserSettingService userSettingService;

    public List<WorkspaceMemberDTO> getWorkspaces() {
        return identityClient.getWorkspaces();
    }

    // Cacheado por 5hs (ver CacheConfiguration.USER_CACHE). @Cacheable en un método void solo
    // cachea la ejecución exitosa (Spring no cachea si el método tira excepción) — una membership
    // confirmada se recuerda por la ventana completa, pero un rechazo siempre se re-verifica
    // contra api-identity, así que sacar a alguien de un workspace corta su acceso de inmediato,
    // no recién cuando expire el cache.
    @Cacheable(cacheNames = CacheConfiguration.USER_CACHE, key = "'membership:' + #workspaceId + ':' + #userId")
    public void verifyUserIsMemberOfWorkspace(Long workspaceId, Long userId) {
        try {
            identityClient.verifyMembership(workspaceId, userId);
        } catch (RestClientResponseException e) {
            throw new PermissionDeniedException("No tienes permiso para operar sobre este recurso");
        }
    }

    @Transactional
    public void createWorkspace(AddWorkspaceRecord addWorkspaceRecord) {
        if (addWorkspaceRecord.description() == null || addWorkspaceRecord.description().isBlank()) {
            throw new BusinessException("La descripción del workspace no puede estar vacía");
        }
        identityClient.createWorkspaces(List.of(addWorkspaceRecord));
    }

    @Transactional
    public void leaveWorkspace(Long workspaceId) {
        Long userId = userService.getMe().id();

        identityClient.leaveWorkspace(workspaceId);

        userSettingService.getDefaultWorkspaceId(userId)
                .filter(workspaceId::equals)
                .ifPresent(id -> userSettingService.deleteByKey(UserSettingKey.DEFAULT_WORKSPACE));

        log.info("Workspace {} abandonado por el usuario {}", workspaceId, userId);
    }

    @Transactional
    public void removeMember(Long workspaceId, Long targetUserId) {
        identityClient.removeMember(workspaceId, targetUserId);

        // Igual que leaveWorkspace: si el workspace del que se lo echó era el default del
        // usuario removido, no lo dejamos apuntando a un workspace al que ya no pertenece.
        userSettingService.getDefaultWorkspaceId(targetUserId)
                .filter(workspaceId::equals)
                .ifPresent(id -> userSettingService.deleteByKeyForUser(targetUserId, UserSettingKey.DEFAULT_WORKSPACE));

        log.info("Miembro {} eliminado del workspace {}", targetUserId, workspaceId);
    }

    public List<WorkspaceInvitationDTO> getMyInvitations() {
        return identityClient.getInvitations();
    }

    public void sendInvitation(Long workspaceId, @Valid WorkspaceSendInvitationDTO body) {
        identityClient.sendInvitation(workspaceId, body);
    }

    public void acceptRejectInvitation(@Valid AcceptRejectInvitationDTO body) {
        identityClient.acceptRejectInvitation(body);
    }

    public List<WorkspaceSentInvitationDTO> getSentInvitations() {
        return identityClient.getSentInvitations();
    }

    public void cancelInvitation(Long invitationId) {
        identityClient.cancelInvitation(invitationId);
    }
}
