package api.m2.file.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.AppFileShare;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.EventType;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.exceptions.ServiceException;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.DownloadableFile;
import api.m2.file.record.FileDTO;
import api.m2.file.record.events.FileTreeChangedEvent;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String ROOT_PATH = "Home";
    private static final long MAX_UPLOAD_SIZE_BYTES = 50L * 1024 * 1024;
    private static final String CHECKSUM_ALGORITHM = "SHA-256";
    private static final int TRASH_RETENTION_DAYS = 1;
    private static final Set<SharePermission> READ_GRANTING_PERMISSIONS =
            EnumSet.of(SharePermission.READ, SharePermission.READ_WRITE);
    private static final Set<SharePermission> WRITE_GRANTING_PERMISSIONS =
            EnumSet.of(SharePermission.WRITE, SharePermission.READ_WRITE);

    private final FileRepository fileRepository;
    private final AppFileShareRepository appFileShareRepository;
    private final StorageProperties storageProperties;
    private final FileDTOMapper fileDTOMapper;
    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final SourceAppResolver sourceAppResolver;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Native workspace membership is the normal path. When it fails, a caller from a different
     * app (resolved from the JWT's app claim, not a client-supplied header) can still reach this
     * exact file if it was explicitly shared with that app via {@link AppFileShare} at the
     * required permission level. This is what makes "Compartir con" in fe-keep actually restrict
     * anything — previously the permission was stored but nothing ever checked it.
     */
    private void verifyAccess(FileEntity file, Long userId, Set<SharePermission> permissionsGrantingAccess) {
        try {
            workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), userId);
        } catch (PermissionDeniedException nativeAccessDenied) {
            boolean hasShareAccess = sourceAppResolver.resolveCallingApp()
                    .flatMap(callingApp -> appFileShareRepository.findByFileIdAndApiName(file.getId(), callingApp))
                    .map(AppFileShare::getPermission)
                    .filter(permissionsGrantingAccess::contains)
                    .isPresent();

            if (!hasShareAccess) {
                throw nativeAccessDenied;
            }
        }
    }

    private void verifyReadAccess(FileEntity file, Long userId) {
        verifyAccess(file, userId, READ_GRANTING_PERMISSIONS);
    }

    private void verifyWriteAccess(FileEntity file, Long userId) {
        verifyAccess(file, userId, WRITE_GRANTING_PERMISSIONS);
    }

    public FileDTO getPersonalFolder(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        FileEntity root = getOrCreateRoot(workspaceId, owner);

        List<FileEntity> files = fileRepository.findByWorkspaceIdAndDeletedAtIsNull(workspaceId);

        var childrenByParentId = files.stream()
                .filter(file -> file.getParentId() != null)
                .collect(Collectors.groupingBy(FileEntity::getParentId));

        var shareWithByFileId = appFileShareRepository.findByFileIdIn(files.stream().map(FileEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(AppFileShare::getFileId,
                        Collectors.mapping(AppFileShare::getApiName, Collectors.toList())));

        return fileDTOMapper.toFileDTO(root, childrenByParentId, shareWithByFileId);
    }

    private FileEntity getOrCreateRoot(Long workspaceId, UserMe owner) {
        return fileRepository.findByWorkspaceIdAndParentIdIsNull(workspaceId)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    FileEntity root = FileEntity.builder()
                            .ownerId(owner.id())
                            .workspaceId(workspaceId)
                            .name(ROOT_PATH)
                            .type(FileType.FOLDER)
                            .location("%s/%s".formatted(storageProperties.basePath(), workspaceId))
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    return fileRepository.save(root);
                });
    }


    public DownloadableFile downloadFile(Long id) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));

        verifyReadAccess(file, userService.getMe().id());

        Path location = validateWithinBasePath(Path.of(file.getLocation()));

        if (file.getType() == FileType.FOLDER) {
            return downloadFolder(file, location);
        }

        if (!Files.isRegularFile(location)) {
            throw new EntityNotFoundException("El archivo no existe en el disco: " + location);
        }

        StreamingResponseBody body = out -> Files.copy(location, out);

        String contentType = file.getContentType() != null ? file.getContentType() : resolveContentType(location);

        return DownloadableFile.builder()
                .body(body)
                .filename(file.getName())
                .contentType(contentType)
                .build();
    }

    private DownloadableFile downloadFolder(FileEntity folder, Path location) {
        if (!Files.isDirectory(location)) {
            throw new EntityNotFoundException("La carpeta no existe en el disco: " + location);
        }

        StreamingResponseBody body = out -> zipDirectory(location, out);

        return DownloadableFile.builder()
                .body(body)
                .filename(folder.getName() + ".zip")
                .contentType("application/zip")
                .build();
    }

    private void zipDirectory(Path sourceDir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out);
             var stream = Files.walk(sourceDir)) {
            for (Path path : stream.filter(p -> !p.equals(sourceDir)).sorted().toList()) {
                String entryName = sourceDir.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zos.putNextEntry(new ZipEntry(entryName + "/"));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    private String resolveContentType(Path location) {
        try {
            String contentType = Files.probeContentType(location);
            return contentType != null ? contentType : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static MessageDigest newChecksumDigest() {
        try {
            return MessageDigest.getInstance(CHECKSUM_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo de checksum no disponible: " + CHECKSUM_ALGORITHM, e);
        }
    }

    /** Best-effort cleanup of a just-written file that turned out to be a duplicate — logs and
     * moves on rather than masking the real (duplicate-content) error with an I/O one. */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("No se pudo limpiar el archivo duplicado '{}'", path, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO uploadFile(Long workspaceId, Long parentId, MultipartFile file) {
        validateUploadableFile(file);

        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        FileEntity parent = resolveParent(workspaceId, parentId, owner);
        Path targetDirectory = Path.of(parent.getLocation());

        String originalFilename = Path.of(Objects.requireNonNull(file.getOriginalFilename())).getFileName().toString();
        String filename = capitalize(originalFilename);
        Path target = validateWithinBasePath(targetDirectory.resolve(filename));

        // CREATE_NEW makes the existence check and the write a single atomic filesystem
        // operation — two concurrent uploads of the same name can no longer both pass a separate
        // Files.exists() check and then race each other into Files.copy(REPLACE_EXISTING).
        MessageDigest digest = newChecksumDigest();
        try {
            Files.createDirectories(target.getParent());
            try (var input = new DigestInputStream(file.getInputStream(), digest);
                    var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                input.transferTo(output);
            }
        } catch (FileAlreadyExistsException e) {
            throw new BusinessException("Ya existe un archivo con el nombre '" + filename + "' en ese destino");
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo: " + target, e);
        }
        String checksum = HexFormat.of().formatHex(digest.digest());

        fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndChecksum(workspaceId, checksum)
                .ifPresent(duplicate -> {
                    deleteQuietly(target);
                    throw new EntityAlreadyExistsException(
                            "El contenido ya existe en este workspace como '%s'".formatted(duplicate.getName()));
                });

        String contentType = file.getContentType() != null ? file.getContentType() : resolveContentType(target);

        LocalDateTime now = LocalDateTime.now();
        FileEntity entity = FileEntity.builder()
                .parentId(parent.getId())
                .ownerId(owner.id())
                .workspaceId(workspaceId)
                .name(filename)
                .type(FileType.FILE)
                .size(file.getSize())
                .contentType(contentType)
                .checksum(checksum)
                .location(target.toString())
                .createdAt(now)
                .updatedAt(now)
                .build();
        fileRepository.save(entity);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(workspaceId, EventType.FILE_ADDED, response));
        return response;
    }

    private static String capitalize(String filename) {
        if (filename.isEmpty()) {
            return filename;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void validateUploadableFile(MultipartFile file) {
        if (file.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido de 50MB");
        }

        String contentType = file.getContentType();
        if (contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            throw new BusinessException("No se permite subir imágenes ni videos");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO createFolder(Long workspaceId, Long parentId, String name) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        FileEntity parent = resolveParent(workspaceId, parentId, owner);
        String folderName = Path.of(name).getFileName().toString();
        Path target = validateWithinBasePath(Path.of(parent.getLocation()).resolve(folderName));

        if (Files.exists(target)) {
            throw new BusinessException("Ya existe un archivo con el nombre '" + folderName + "' en ese destino");
        }

        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta: " + target, e);
        }

        LocalDateTime now = LocalDateTime.now();
        FileEntity entity = FileEntity.builder()
                .parentId(parent.getId())
                .ownerId(owner.id())
                .workspaceId(workspaceId)
                .name(folderName)
                .type(FileType.FOLDER)
                .location(target.toString())
                .createdAt(now)
                .updatedAt(now)
                .build();
        fileRepository.save(entity);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(workspaceId, EventType.FILE_ADDED, response));
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO renameNode(Long id, String name) {
        FileEntity entity = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));

        verifyWriteAccess(entity, userService.getMe().id());

        if (entity.getParentId() == null) {
            throw new BusinessException("No se puede renombrar la carpeta raíz");
        }

        String newName = Path.of(name).getFileName().toString();
        Path oldLocation = Path.of(entity.getLocation());
        Path newLocation = validateWithinBasePath(oldLocation.resolveSibling(newName));

        if (Files.exists(newLocation)) {
            throw new BusinessException("Ya existe un archivo con el nombre '" + newName + "' en ese destino");
        }

        try {
            Files.move(oldLocation, newLocation);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo renombrar: " + oldLocation, e);
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setName(newName);
        entity.setLocation(newLocation.toString());
        entity.setUpdatedAt(now);

        if (entity.getType() == FileType.FOLDER) {
            relocateDescendants(entity, oldLocation, newLocation, now);
        }

        fileRepository.save(entity);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_UPDATED, response));
        return response;
    }

    /**
     * Soft-delete: moves the node (and, for a folder, its whole subtree) to the trash instead of
     * touching disk. Nothing is actually removed until {@link #purgeExpiredTrash()} sweeps it up
     * after {@value #TRASH_RETENTION_DAYS} day(s) — see {@link #restoreNode(Long)} to undo this.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long id) {
        FileEntity entity = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));

        verifyWriteAccess(entity, userService.getMe().id());

        if (entity.getParentId() == null) {
            throw new BusinessException("No se puede eliminar la carpeta raíz");
        }
        if (entity.getDeletedAt() != null) {
            throw new BusinessException("Ya está en la papelera");
        }

        List<FileEntity> subtree = collectSubtree(entity, fileRepository.findByWorkspaceIdAndDeletedAtIsNull(entity.getWorkspaceId()));
        LocalDateTime now = LocalDateTime.now();
        subtree.forEach(node -> node.setDeletedAt(now));
        fileRepository.saveAll(subtree);

        FileDTO deletedNode = FileDTO.builder().id(entity.getId().toString()).name(entity.getName()).build();
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_DELETED, deletedNode));
    }

    /** Undoes {@link #deleteNode(Long)}: clears deletedAt on the node and whatever was trashed
     * alongside it, restoring the whole subtree together. */
    @Transactional(rollbackFor = Exception.class)
    public FileDTO restoreNode(Long id) {
        FileEntity entity = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));

        verifyWriteAccess(entity, userService.getMe().id());

        if (entity.getDeletedAt() == null) {
            throw new BusinessException("El archivo no está en la papelera");
        }

        List<FileEntity> subtree = collectSubtree(entity, fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(entity.getWorkspaceId()));
        subtree.forEach(node -> node.setDeletedAt(null));
        fileRepository.saveAll(subtree);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_ADDED, response));
        return response;
    }

    public List<FileDTO> listTrash(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        return fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(workspaceId).stream()
                .map(this::toResponseNode)
                .toList();
    }

    /**
     * Sweeps anything trashed more than {@value #TRASH_RETENTION_DAYS} day(s) ago and actually
     * removes it — disk first (deepest paths first, so a folder only comes off once it's empty),
     * then the DB row, one node at a time so a disk failure on one item doesn't lose track of the
     * rest: it's just retried on the next run instead of being deleted from the DB regardless.
     */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    @Transactional(rollbackFor = Exception.class)
    public void purgeExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TRASH_RETENTION_DAYS);
        List<FileEntity> expired = fileRepository.findByDeletedAtBefore(cutoff).stream()
                .sorted(Comparator.comparingInt((FileEntity e) -> Path.of(e.getLocation()).getNameCount()).reversed())
                .toList();

        if (expired.isEmpty()) {
            return;
        }
        log.info("Purgando {} elementos de la papelera con más de {} día(s)", expired.size(), TRASH_RETENTION_DAYS);

        for (FileEntity entity : expired) {
            try {
                Files.deleteIfExists(Path.of(entity.getLocation()));
            } catch (IOException e) {
                log.warn("No se pudo purgar '{}' del disco, se reintentará en el próximo ciclo", entity.getLocation(), e);
                continue;
            }
            fileRepository.delete(entity);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO moveNode(Long id, Long newParentId) {
        var owner = userService.getMe();
        FileEntity entity = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));

        verifyWriteAccess(entity, owner.id());

        if (entity.getParentId() == null) {
            throw new BusinessException("No se puede mover la carpeta raíz");
        }

        if (Objects.equals(entity.getParentId(), newParentId)) {
            return toResponseNode(entity);
        }

        Map<Long, List<FileEntity>> childrenByParentId = childrenByParentId(entity.getWorkspaceId());

        FileEntity newParent = resolveParent(entity.getWorkspaceId(), newParentId, owner);

        if (newParent.getId().equals(entity.getId())
                || isDescendant(entity.getId(), newParent.getId(), childrenByParentId)) {
            throw new BusinessException("No se puede mover una carpeta dentro de sí misma o de una subcarpeta suya");
        }

        Path oldLocation = Path.of(entity.getLocation());
        Path newLocation = validateWithinBasePath(Path.of(newParent.getLocation()).resolve(entity.getName()));

        if (Files.exists(newLocation)) {
            throw new BusinessException(
                    "Ya existe un archivo con el nombre '" + entity.getName() + "' en ese destino");
        }

        try {
            Files.move(oldLocation, newLocation);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo mover: " + oldLocation, e);
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setParentId(newParent.getId());
        entity.setLocation(newLocation.toString());
        entity.setUpdatedAt(now);

        if (entity.getType() == FileType.FOLDER) {
            relocateDescendants(entity, oldLocation, newLocation, now);
        }

        fileRepository.save(entity);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_UPDATED, response));
        return response;
    }

    private boolean isDescendant(Long ancestorId, Long candidateId, Map<Long, List<FileEntity>> childrenByParentId) {
        for (FileEntity child : childrenByParentId.getOrDefault(ancestorId, List.of())) {
            if (child.getId().equals(candidateId) || isDescendant(child.getId(), candidateId, childrenByParentId)) {
                return true;
            }
        }
        return false;
    }

    private void relocateDescendants(FileEntity folder, Path oldLocation, Path newLocation, LocalDateTime now) {
        Map<Long, List<FileEntity>> childrenByParentId = childrenByParentId(folder.getWorkspaceId());

        List<FileEntity> descendants = new ArrayList<>();
        collectDescendants(folder.getId(), childrenByParentId, descendants);

        for (FileEntity descendant : descendants) {
            Path relative = oldLocation.relativize(Path.of(descendant.getLocation()));
            descendant.setLocation(newLocation.resolve(relative).toString());
            descendant.setUpdatedAt(now);
        }
        fileRepository.saveAll(descendants);
    }

    private void collectDescendants(Long parentId, Map<Long, List<FileEntity>> childrenByParentId, List<FileEntity> acc) {
        for (FileEntity child : childrenByParentId.getOrDefault(parentId, List.of())) {
            acc.add(child);
            collectDescendants(child.getId(), childrenByParentId, acc);
        }
    }

    private Map<Long, List<FileEntity>> childrenByParentId(Long workspaceId) {
        return fileRepository.findByWorkspaceIdAndDeletedAtIsNull(workspaceId).stream()
                .filter(f -> f.getParentId() != null)
                .collect(Collectors.groupingBy(FileEntity::getParentId));
    }

    private static Map<Long, List<FileEntity>> groupByParentId(List<FileEntity> files) {
        return files.stream()
                .filter(f -> f.getParentId() != null)
                .collect(Collectors.groupingBy(FileEntity::getParentId));
    }

    /** The node plus every descendant found within {@code pool} — used to cascade a trash/restore
     * to a whole folder at once, since a folder and its children always move through the trash
     * together. */
    private List<FileEntity> collectSubtree(FileEntity root, List<FileEntity> pool) {
        Map<Long, List<FileEntity>> childrenByParentId = groupByParentId(pool);
        List<FileEntity> subtree = new ArrayList<>();
        subtree.add(root);
        collectDescendants(root.getId(), childrenByParentId, subtree);
        return subtree;
    }

    private FileDTO toResponseNode(FileEntity entity) {
        return FileDTO.builder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .metadata(FileDTO.Metadata.builder()
                        .type(entity.getType())
                        .size(entity.getSize())
                        .lastModified(entity.getUpdatedAt())
                        .createdAt(entity.getCreatedAt())
                        .contentType(entity.getContentType())
                        .checksum(entity.getChecksum())
                        .build())
                .build();
    }

    private Path validateWithinBasePath(Path path) {
        Path basePath = Path.of(storageProperties.basePath()).normalize().toAbsolutePath();
        Path normalized = path.normalize().toAbsolutePath();

        if (!normalized.startsWith(basePath)) {
            throw new ServiceException("La ubicación está fuera del directorio permitido");
        }

        return normalized;
    }

    private FileEntity resolveParent(Long workspaceId, Long parentId, UserMe owner) {
        if (parentId == null) {
            return getOrCreateRoot(workspaceId, owner);
        }

        FileEntity parent = fileRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró la carpeta con id " + parentId));

        if (parent.getType() != FileType.FOLDER) {
            throw new BusinessException("El destino no es una carpeta");
        }

        if (!parent.getWorkspaceId().equals(workspaceId)) {
            throw new PermissionDeniedException("No tiene permisos sobre esta carpeta");
        }

        return parent;
    }
}
