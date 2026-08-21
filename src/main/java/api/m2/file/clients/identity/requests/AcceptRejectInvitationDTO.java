package api.m2.file.clients.identity.requests;

import jakarta.validation.constraints.NotNull;

public record AcceptRejectInvitationDTO(
        @NotNull(message = "El id de la invitación es requerido")
        Long id,
        boolean status) {
}
