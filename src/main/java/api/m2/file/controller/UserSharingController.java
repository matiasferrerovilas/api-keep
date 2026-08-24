package api.m2.file.controller;

import api.m2.file.record.CreateUserFileShareRequest;
import api.m2.file.record.FileDTO;
import api.m2.file.record.UpdateUserFileShareRequest;
import api.m2.file.record.UserFileShareResponse;
import api.m2.file.service.FileService;
import api.m2.file.service.UserSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/shares/users")
@Tag(name = "User Shares", description = "API para compartir archivos y carpetas con otra persona (por email), "
        + "a diferencia de /v1/shares que comparte con otra app")
public class UserSharingController {

    private final UserSharingService userSharingService;
    private final FileService fileService;

    @Operation(
            summary = "Compartir un archivo o carpeta con una persona",
            description = "Otorga a otro usuario (resuelto por email) acceso `READ`/`WRITE`/`READ_WRITE` sobre "
                    + "un archivo o carpeta, opcionalmente hasta una fecha de vencimiento. Compartir una carpeta "
                    + "cubre todo lo que tenga adentro, incluido lo que se suba después. El caller debe "
                    + "pertenecer al workspace del archivo.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Compartido correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo no existe, o ningún usuario tiene ese email", content = @Content),
                    @ApiResponse(responseCode = "409", description = "Ya está compartido con esa persona", content = @Content),
            }
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserFileShareResponse shareWithUser(@Valid @RequestBody CreateUserFileShareRequest request) {
        return userSharingService.shareWithUser(request);
    }

    @Operation(
            summary = "Listar las personas con las que se compartió un archivo o carpeta",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Listado obtenido correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo no existe", content = @Content),
            }
    )
    @GetMapping
    public List<UserFileShareResponse> getShares(
            @Parameter(description = "ID del archivo o carpeta") @RequestParam Long fileId) {
        return userSharingService.getShares(fileId);
    }

    @Operation(
            summary = "Cambiar el permiso o el vencimiento de un share existente",
            description = "Reemplaza el permiso y el vencimiento del share tal como se mandan — no es un patch "
                    + "parcial, es lo mismo que crearlo de nuevo pero preservando su identidad e historial en vez "
                    + "de revocar y recrear. El caller debe pertenecer al workspace del archivo.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Share actualizado correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El share o el archivo no existen", content = @Content),
            }
    )
    @PatchMapping("/{id}")
    public UserFileShareResponse updateShare(
            @Parameter(description = "ID del share") @PathVariable Long id,
            @Valid @RequestBody UpdateUserFileShareRequest request) {
        return userSharingService.updateShare(id, request);
    }

    @Operation(
            summary = "Revocar un share con una persona",
            description = "El caller debe pertenecer al workspace del archivo.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Share revocado correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El share o el archivo no existen", content = @Content),
            }
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare(@Parameter(description = "ID del share") @PathVariable Long id) {
        userSharingService.revokeShare(id);
    }

    @Operation(
            summary = "Listar lo que otras personas compartieron conmigo",
            description = "Devuelve los archivos/carpetas actualmente compartidos con el usuario autenticado "
                    + "(excluye vencidos), sin importar a qué workspace pertenezcan — es cómo alguien que no es "
                    + "miembro de un workspace se entera de que le compartieron algo.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Listado obtenido correctamente"),
            }
    )
    @GetMapping("/shared-with-me")
    public List<FileDTO> getSharedWithMe() {
        return fileService.listSharedWithMe();
    }
}
