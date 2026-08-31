package api.m2.file.clients.identity.requests;

import api.m2.file.enums.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record WorkspaceSendInvitationDTO(
        Long workspaceId,
        List<String> emails,
        // Rol con el que se une cada invitado si acepta — lo elige quien invita desde el frontend.
        // api-identity valida que no sea OWNER (no se puede invitar directamente como OWNER).
        @NotNull(message = "El rol es requerido")
        WorkspaceRole role) {
}
