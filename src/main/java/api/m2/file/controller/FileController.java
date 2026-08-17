package api.m2.file.controller;

import api.m2.file.record.CreateFolderRequest;
import api.m2.file.record.DownloadableFile;
import api.m2.file.record.FileDTO;
import api.m2.file.record.MoveNodeRequest;
import api.m2.file.record.RenameNodeRequest;
import api.m2.file.service.FileService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
public class FileController {

    private final FileService fileService;

    @GetMapping("/tree")
    @ApiResponse(responseCode = "200", description = "Listado retornado correctamente")
    public FileDTO listFiles(@RequestParam Long workspaceId) {
        return fileService.getPersonalFolder(workspaceId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable Long id) {
        DownloadableFile file = fileService.downloadFile(id);

        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(file.filename())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.body());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileDTO uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam Long workspaceId,
            @RequestParam(value = "parentId", required = false) Long parentId) {
        return fileService.uploadFile(workspaceId, parentId, file);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileDTO createFolder(@Valid @RequestBody CreateFolderRequest request) {
        return fileService.createFolder(request.workspaceId(), request.parentId(), request.name());
    }

    @PatchMapping("/{id}")
    public FileDTO renameNode(@PathVariable Long id, @Valid @RequestBody RenameNodeRequest request) {
        return fileService.renameNode(id, request.name());
    }

    @PatchMapping("/{id}/move")
    public FileDTO moveNode(@PathVariable Long id, @RequestBody MoveNodeRequest request) {
        return fileService.moveNode(id, request.parentId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNode(@PathVariable Long id) {
        fileService.deleteNode(id);
    }

    @GetMapping("/trash")
    @ApiResponse(responseCode = "200", description = "Elementos en la papelera")
    public List<FileDTO> listTrash(@RequestParam Long workspaceId) {
        return fileService.listTrash(workspaceId);
    }

    @PostMapping("/{id}/restore")
    public FileDTO restoreNode(@PathVariable Long id) {
        return fileService.restoreNode(id);
    }
}
