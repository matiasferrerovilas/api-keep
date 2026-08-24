package api.m2.file.record;

import api.m2.file.enums.SharePermission;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** Replaces the share's permission/expiration wholesale, same as creating one — not a true
 * partial patch: sending {@code expiresAt: null} clears it, just like on create. */
public record UpdateUserFileShareRequest(
        @NotNull(message = "El permiso es requerido")
        SharePermission permission,
        /** Null = sin vencimiento. */
        LocalDateTime expiresAt) {
}
