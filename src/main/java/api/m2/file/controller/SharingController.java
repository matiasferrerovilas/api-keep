package api.m2.file.controller;

import api.m2.file.record.CreateFileShareRequest;
import api.m2.file.record.FileShareResponse;
import api.m2.file.service.SharingService;
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
@RequestMapping("/v1/shares")
@Tag(name = "Shares", description = "API para compartir archivos y carpetas con otras apis (app-a-app)")
public class SharingController {

    private final SharingService sharingService;

    @Operation(
            summary = "Compartir un archivo o carpeta con una api",
            description = "Otorga a otra app (identificada por su `apiName`) acceso `READ`/`WRITE`/`READ_WRITE` "
                    + "sobre un archivo o carpeta. El caller debe pertenecer al workspace del archivo.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Compartido correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo no existe", content = @Content),
                    @ApiResponse(responseCode = "409", description = "Ya existe un share con esa api para este archivo", content = @Content),
            }
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileShareResponse shareFile(@Valid @RequestBody CreateFileShareRequest request) {
        return sharingService.shareFile(request);
    }

    @Operation(
            summary = "Listar las apis con las que se compartió un archivo o carpeta",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Listado obtenido correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo no existe", content = @Content),
            }
    )
    @GetMapping
    public List<FileShareResponse> getShares(
            @Parameter(description = "ID del archivo o carpeta") @RequestParam Long fileId) {
        return sharingService.getShares(fileId);
    }

    @Operation(
            summary = "Revocar un share",
            description = "Elimina el acceso otorgado a una api sobre un archivo o carpeta. El caller debe "
                    + "pertenecer al workspace del archivo.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Share revocado correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece al workspace del archivo", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El share o el archivo no existen", content = @Content),
            }
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare(@Parameter(description = "ID del share") @PathVariable Long id) {
        sharingService.revokeShare(id);
    }
}
