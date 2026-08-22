package api.m2.file.record;

import api.m2.file.enums.SharePermission;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record CreateUserFileShareRequest(
        @NotNull(message = "El archivo o carpeta es requerido")
        Long fileId,
        @NotBlank(message = "El email es requerido")
        @Email(message = "El email no es válido")
        String email,
        @NotNull(message = "El permiso es requerido")
        SharePermission permission,
        /** Null = sin vencimiento. */
        LocalDateTime expiresAt) {
}
