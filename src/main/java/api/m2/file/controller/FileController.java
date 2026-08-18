package api.m2.file.controller;

import api.m2.file.record.CreateFolderRequest;
import api.m2.file.record.DownloadableFile;
import api.m2.file.record.FileDTO;
import api.m2.file.record.FileSearchResult;
import api.m2.file.record.MoveNodeRequest;
import api.m2.file.record.RenameNodeRequest;
import api.m2.file.record.SetFavoriteRequest;
import api.m2.file.record.WorkspaceUsageResponse;
import api.m2.file.service.FileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/folders")
@Tag(name = "Files", description = "Árbol de archivos y carpetas de un workspace: subida, descarga, organización y papelera")
public class FileController {

    private final FileService fileService;

    @Operation(
            summary = "Obtener el árbol de archivos del workspace",
            description = "Retorna la carpeta raíz (`Home`) del workspace con todo su árbol de subcarpetas y archivos anidados.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Árbol obtenido correctamente",
                            content = @Content(schema = @Schema(implementation = FileDTO.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/tree")
    public FileDTO listFiles(
            @Parameter(description = "ID del workspace") @RequestParam Long workspaceId) {
        return fileService.getPersonalFolder(workspaceId);
    }

    @Operation(
            summary = "Descargar un archivo o carpeta",
            description = "Descarga un archivo tal cual, o una carpeta completa como zip generado on-the-fly.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Contenido devuelto como stream de descarga"),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo o carpeta no existe", content = @Content),
            }
    )
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @Parameter(description = "ID del archivo o carpeta a descargar") @PathVariable Long id) {
        DownloadableFile file = fileService.downloadFile(id);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(file.filename())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.body());
    }

    @Operation(
            summary = "Subir un archivo",
            description = "Sube un archivo al workspace, opcionalmente dentro de una carpeta (`parentId`; sin especificar, va a la raíz). "
                    + "Rechaza imágenes/videos, archivos que superen el límite configurado, y duplicados exactos "
                    + "(mismo checksum SHA-256 en cualquier parte del workspace).",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Archivo subido correctamente"),
                    @ApiResponse(responseCode = "400", description = "Tipo de archivo no permitido, tamaño excedido, "
                            + "o ya existe un archivo con ese nombre en el destino", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre la carpeta destino", content = @Content),
                    @ApiResponse(responseCode = "404", description = "La carpeta destino (`parentId`) no existe", content = @Content),
                    @ApiResponse(responseCode = "409", description = "Ya existe un archivo idéntico (mismo checksum) en el workspace",
                            content = @Content),
            }
    )
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileDTO uploadFile(
            @Parameter(description = "Archivo a subir") @RequestParam("file") MultipartFile file,
            @Parameter(description = "ID del workspace") @RequestParam Long workspaceId,
            @Parameter(description = "ID de la carpeta destino; si se omite, va a la raíz del workspace")
            @RequestParam(value = "parentId", required = false) Long parentId) {
        return fileService.uploadFile(workspaceId, parentId, file);
    }

    @Operation(
            summary = "Crear una carpeta",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Carpeta creada correctamente"),
                    @ApiResponse(responseCode = "400", description = "Ya existe una carpeta con ese nombre en el destino", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre la carpeta destino", content = @Content),
                    @ApiResponse(responseCode = "404", description = "La carpeta destino (`parentId`) no existe", content = @Content),
            }
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileDTO createFolder(@Valid @RequestBody CreateFolderRequest request) {
        return fileService.createFolder(request.workspaceId(), request.parentId(), request.name());
    }

    @Operation(
            summary = "Renombrar un archivo o carpeta",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Renombrado correctamente"),
                    @ApiResponse(responseCode = "400", description = "Es la carpeta raíz (no se puede renombrar), "
                            + "o ya existe un archivo/carpeta con ese nombre en el mismo destino", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo o carpeta no existe", content = @Content),
            }
    )
    @PatchMapping("/{id}")
    public FileDTO renameNode(
            @Parameter(description = "ID del archivo o carpeta a renombrar") @PathVariable Long id,
            @Valid @RequestBody RenameNodeRequest request) {
        return fileService.renameNode(id, request.name());
    }

    @Operation(
            summary = "Mover un archivo o carpeta",
            description = "Mueve el nodo a un nuevo padre (`parentId`; `null` para moverlo a la raíz del workspace).",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Movido correctamente"),
                    @ApiResponse(responseCode = "400", description = "Es la carpeta raíz, se intenta mover dentro de sí misma "
                            + "o de una subcarpeta suya, o ya existe un archivo/carpeta con ese nombre en el destino", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta o el destino", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo/carpeta o el destino no existen", content = @Content),
            }
    )
    @PatchMapping("/{id}/move")
    public FileDTO moveNode(
            @Parameter(description = "ID del archivo o carpeta a mover") @PathVariable Long id,
            @RequestBody MoveNodeRequest request) {
        return fileService.moveNode(id, request.parentId());
    }

    @Operation(
            summary = "Enviar un archivo o carpeta a la papelera",
            description = "Soft-delete: el nodo queda en la papelera y se purga automáticamente al día siguiente si no se restaura antes.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Enviado a la papelera correctamente",
                            content = @Content(schema = @Schema(hidden = true))),
                    @ApiResponse(responseCode = "400", description = "Es la carpeta raíz, o ya está en la papelera", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo o carpeta no existe", content = @Content),
            }
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(@Parameter(description = "ID del archivo o carpeta a eliminar") @PathVariable Long id) {
        fileService.deleteNode(id);
    }

    @Operation(
            summary = "Listar los elementos en la papelera",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Elementos en la papelera"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/trash")
    public List<FileDTO> listTrash(@Parameter(description = "ID del workspace") @RequestParam Long workspaceId) {
        return fileService.listTrash(workspaceId);
    }

    @Operation(
            summary = "Restaurar un archivo o carpeta de la papelera",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Restaurado correctamente"),
                    @ApiResponse(responseCode = "400", description = "El archivo o carpeta no está en la papelera", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo o carpeta no existe", content = @Content),
            }
    )
    @PostMapping("/{id}/restore")
    public FileDTO restoreNode(@Parameter(description = "ID del archivo o carpeta a restaurar") @PathVariable Long id) {
        return fileService.restoreNode(id);
    }

    @Operation(
            summary = "Consultar el uso de almacenamiento del workspace",
            description = "Retorna los bytes actualmente ocupados por archivos no borrados del workspace y la cuota "
                    + "configurada (`app.storage.workspace-quota`), para mostrar un indicador de uso en el cliente.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Uso obtenido correctamente",
                            content = @Content(schema = @Schema(implementation = WorkspaceUsageResponse.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/usage")
    public WorkspaceUsageResponse getWorkspaceUsage(
            @Parameter(description = "ID del workspace") @RequestParam Long workspaceId) {
        return fileService.getWorkspaceUsage(workspaceId);
    }

    @Operation(
            summary = "Buscar archivos y carpetas por nombre y contenido",
            description = "Búsqueda case-insensitive (`LIKE '%query%'`) scopeada al workspace, contra el nombre de "
                    + "cada archivo/carpeta y, para archivos de texto plano o Markdown (`.txt`/`.md`), contra su "
                    + "contenido extraído en la subida. No se hace extracción de contenido para PDFs, imágenes u "
                    + "otros binarios: eso requeriría una librería pesada (ej. Apache Tika) y está fuera de "
                    + "alcance para este volumen de datos. Cada resultado incluye el breadcrumb de carpetas "
                    + "ancestras para que el cliente pueda navegar directo sin resolver `parentId` a mano.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Resultados de la búsqueda (lista vacía si no hay matches o la query está vacía)",
                            content = @Content(schema = @Schema(implementation = FileSearchResult.class))
                    ),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/search")
    public List<FileSearchResult> searchFiles(
            @Parameter(description = "ID del workspace") @RequestParam Long workspaceId,
            @Parameter(description = "Texto a buscar en el nombre y, si aplica, el contenido") @RequestParam String query) {
        return fileService.searchFiles(workspaceId, query);
    }

    @Operation(
            summary = "Marcar/desmarcar un archivo o carpeta como favorito",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Favorito actualizado correctamente"),
                    @ApiResponse(responseCode = "403", description = "Sin permisos sobre el archivo/carpeta", content = @Content),
                    @ApiResponse(responseCode = "404", description = "El archivo o carpeta no existe", content = @Content),
            }
    )
    @PatchMapping("/{id}/favorite")
    public FileDTO setFavorite(
            @Parameter(description = "ID del archivo o carpeta") @PathVariable Long id,
            @RequestBody SetFavoriteRequest request) {
        return fileService.setFavorite(id, request.favorite());
    }

    @Operation(
            summary = "Listar los favoritos del workspace",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Favoritos obtenidos correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/favorites")
    public List<FileDTO> listFavorites(@Parameter(description = "ID del workspace") @RequestParam Long workspaceId) {
        return fileService.listFavorites(workspaceId);
    }

    @Operation(
            summary = "Listar los archivos accedidos recientemente",
            description = "Retorna archivos y carpetas del workspace ordenados por último acceso descendente. Un "
                    + "nodo solo cuenta como \"accedido\" cuando efectivamente se descarga/abre (ver `GET /{id}/download`), "
                    + "nunca por aparecer en el listado del árbol — los que nunca se abrieron quedan excluidos.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Recientes obtenidos correctamente"),
                    @ApiResponse(responseCode = "403", description = "El usuario no pertenece a ese workspace", content = @Content),
            }
    )
    @GetMapping("/recent")
    public List<FileDTO> listRecent(
            @Parameter(description = "ID del workspace") @RequestParam Long workspaceId,
            @Parameter(description = "Cantidad máxima de resultados (default 20)")
            @RequestParam(required = false) Integer limit) {
        return fileService.listRecent(workspaceId, limit);
    }
}
