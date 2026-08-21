package api.m2.file.controller;

import api.m2.file.clients.identity.requests.AcceptRejectInvitationDTO;
import api.m2.file.clients.identity.requests.AddWorkspaceRecord;
import api.m2.file.clients.identity.requests.WorkspaceSendInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceInvitationDTO;
import api.m2.file.clients.identity.response.WorkspaceMemberDTO;
import api.m2.file.service.workspace.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/workspace")
@Tag(name = "Workspaces", description = "API para la gestión de workspaces (proxy hacia api-identity, que es quien los posee)")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @Operation(
            summary = "Crear un nuevo workspace",
            description = "Crea un workspace asociado al usuario autenticado, delegando la persistencia a api-identity.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Workspace creado correctamente"),
                    @ApiResponse(responseCode = "400", description = "La descripción del workspace está vacía", content = @Content),
            }
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void createWorkspace(@Valid @RequestBody AddWorkspaceRecord body) {
        workspaceService.createWorkspace(body);
    }

    @Operation(
            summary = "Listar workspaces del usuario",
            description = "Retorna los workspaces de los que el usuario autenticado es miembro, junto con su rol en cada uno.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Workspaces obtenidos correctamente")
            }
    )
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<WorkspaceMemberDTO> getWorkspaces() {
        return workspaceService.getWorkspaces();
    }

    @Operation(
            summary = "Salir de un workspace",
            description = "El usuario autenticado abandona un workspace.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Salida del workspace exitosa")
            }
    )
    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void exitWorkspace(@PathVariable Long workspaceId) {
        workspaceService.leaveWorkspace(workspaceId);
    }

    @Operation(
            summary = "Eliminar un miembro de un workspace",
            description = "Elimina al usuario indicado del workspace. Requiere ser OWNER del workspace o "
                    + "tener el rol global ROLE_ADMIN (verificado por api-identity).",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Miembro eliminado"),
                    @ApiResponse(responseCode = "403", description = "Quien invoca no es OWNER ni administrador")
            }
    )
    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable Long workspaceId, @PathVariable Long userId) {
        workspaceService.removeMember(workspaceId, userId);
    }

    @Operation(
            summary = "Listar invitaciones recibidas",
            description = "Devuelve todas las invitaciones pendientes del usuario autenticado.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Invitaciones obtenidas correctamente")
            }
    )
    @GetMapping("/invitations")
    @ResponseStatus(HttpStatus.OK)
    public List<WorkspaceInvitationDTO> getMyInvitations() {
        return workspaceService.getMyInvitations();
    }

    @Operation(
            summary = "Invitar a un usuario a un workspace",
            description = "Envía una invitación por email a uno o más usuarios para unirse al workspace.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Invitación enviada correctamente")
            }
    )
    @PostMapping("/{workspaceId}/invitations")
    @ResponseStatus(HttpStatus.OK)
    public void sendInvitation(@PathVariable Long workspaceId, @Valid @RequestBody WorkspaceSendInvitationDTO body) {
        workspaceService.sendInvitation(workspaceId, body);
    }

    @Operation(
            summary = "Aceptar o rechazar una invitación",
            description = "Acepta o rechaza una invitación a un workspace recibida por el usuario autenticado.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Invitación actualizada correctamente")
            }
    )
    @PatchMapping("/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.OK)
    public void acceptRejectInvitation(@PathVariable Long invitationId, @RequestBody AcceptRejectInvitationDTO invitationDTO) {
        workspaceService.acceptRejectInvitation(invitationDTO);
    }
}
