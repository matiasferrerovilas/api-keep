package api.m2.file.service;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.configuration.properties.StorageProperties;
import api.m2.file.entity.AppFileShare;
import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.EventType;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.enums.FileType;
import api.m2.file.enums.SharePermission;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.exceptions.ServiceException;
import api.m2.file.mappers.FileActivityMapper;
import api.m2.file.mappers.FileDTOMapper;
import api.m2.file.record.DownloadableFile;
import api.m2.file.record.FileActivityResponse;
import api.m2.file.record.FileDTO;
import api.m2.file.record.FileSearchResult;
import api.m2.file.record.WorkspaceUsageResponse;
import api.m2.file.record.events.FileTreeChangedEvent;
import api.m2.file.record.events.UserFileShareEvent;
import api.m2.file.repository.AppFileShareRepository;
import api.m2.file.repository.FileActivityRepository;
import api.m2.file.repository.FileRepository;
import api.m2.file.repository.UserFileShareRepository;
import api.m2.file.service.storage.StorageAdapter;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String ROOT_PATH = "Home";
    private static final String CHECKSUM_ALGORITHM = "SHA-256";
    private static final int TRASH_RETENTION_DAYS = 1;
    private static final int DEFAULT_RECENT_LIMIT = 20;
    // Content search is intentionally limited to plain-text and Markdown: their bytes ARE the
    // searchable text already, no parsing needed. PDFs, images and other binary formats would
    // need a heavy extraction library (e.g. Apache Tika) to pull searchable text out of them —
    // explicitly out of scope for this home-lab-scale feature.
    private static final Set<String> TEXT_SEARCHABLE_EXTENSIONS = Set.of(".txt", ".md");
    private static final Set<SharePermission> READ_GRANTING_PERMISSIONS =
            EnumSet.of(SharePermission.READ, SharePermission.READ_WRITE);
    private static final Set<SharePermission> WRITE_GRANTING_PERMISSIONS =
            EnumSet.of(SharePermission.WRITE, SharePermission.READ_WRITE);
    private static final Duration EXPIRING_SHARE_REMINDER_WINDOW = Duration.ofHours(24);

    private final FileRepository fileRepository;
    private final AppFileShareRepository appFileShareRepository;
    private final UserFileShareRepository userFileShareRepository;
    private final StorageProperties storageProperties;
    private final StorageAdapter storageAdapter;
    private final FileDTOMapper fileDTOMapper;
    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final SourceAppResolver sourceAppResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final FileActivityRepository fileActivityRepository;
    private final FileActivityMapper fileActivityMapper;
    private final FileActivityLogService fileActivityLogService;

    /**
     * Native workspace membership is the normal path. When it fails, either of two fallbacks can
     * still grant access to this exact operation:
     *  - a caller from a different app (resolved from the JWT's app claim, not a client-supplied
     *    header) if the file was explicitly shared with that app via {@link AppFileShare};
     *  - a caller who is a person the file (or one of its ancestor folders) was shared with via
     *    {@link UserFileShare}, and that grant hasn't expired.
     * Neither fallback distinguishes "never shared" from "shared but expired/wrong permission" in
     * its result — both just fall through to the same {@code PermissionDeniedException}, so a
     * probing request can't tell the two apart.
     */
    private void verifyAccess(FileEntity file, Long userId, Set<SharePermission> permissionsGrantingAccess) {
        try {
            workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), userId);
        } catch (PermissionDeniedException nativeAccessDenied) {
            boolean hasAppShareAccess = sourceAppResolver.resolveCallingApp()
                    .flatMap(callingApp -> appFileShareRepository.findByFileIdAndApiName(file.getId(), callingApp))
                    .map(AppFileShare::getPermission)
                    .filter(permissionsGrantingAccess::contains)
                    .isPresent();

            if (!hasAppShareAccess && !hasUserShareAccess(file, userId, permissionsGrantingAccess)) {
                throw nativeAccessDenied;
            }
        }
    }

    /**
     * Walks from {@code file} up through {@code parentId} (inclusive of {@code file} itself),
     * checking each ancestor for a {@link UserFileShare} that grants {@code userId} one of
     * {@code permissionsGrantingAccess} and hasn't expired. This is what makes sharing a folder
     * cover everything inside it — including files added after the share was created, since
     * nothing is precomputed, the walk just runs live against the tree as it currently is. A
     * share found on one ancestor that's expired or the wrong permission doesn't stop the walk: a
     * different ancestor further up could still grant access.
     */
    private boolean hasUserShareAccess(FileEntity file, Long userId, Set<SharePermission> permissionsGrantingAccess) {
        LocalDateTime now = LocalDateTime.now();
        FileEntity current = file;
        while (current != null) {
            boolean grantsAccess = userFileShareRepository.findByFileIdAndSharedWithUserId(current.getId(), userId)
                    .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(now))
                    .map(UserFileShare::getPermission)
                    .filter(permissionsGrantingAccess::contains)
                    .isPresent();

            if (grantsAccess) {
                return true;
            }

            current = current.getParentId() != null
                    ? fileRepository.findById(current.getParentId()).orElse(null)
                    : null;
        }
        return false;
    }

    private void verifyReadAccess(FileEntity file, Long userId) {
        verifyAccess(file, userId, READ_GRANTING_PERMISSIONS);
    }

    private void verifyWriteAccess(FileEntity file, Long userId) {
        verifyAccess(file, userId, WRITE_GRANTING_PERMISSIONS);
    }

    /** {@code fileRepository.findById(id).orElseThrow(...)} + {@link #verifyReadAccess} — this
     * exact pair used to be copy-pasted at every read-only call site below, each with its own
     * 404 message wording. */
    private FileEntity requireFileWithReadAccess(Long id, Long userId) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));
        verifyReadAccess(file, userId);
        return file;
    }

    /** Same as {@link #requireFileWithReadAccess}, but for the mutating call sites that need
     * {@link #verifyWriteAccess} instead. */
    private FileEntity requireFileWithWriteAccess(Long id, Long userId) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + id));
        verifyWriteAccess(file, userId);
        return file;
    }

    public FileDTO getPersonalFolder(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        FileEntity root = getOrCreateRoot(workspaceId, owner);

        List<FileEntity> files = fileRepository.findByWorkspaceIdAndDeletedAtIsNull(workspaceId);

        var childrenByParentId = files.stream()
                .filter(file -> file.getParentId() != null)
                .collect(Collectors.groupingBy(FileEntity::getParentId));

        List<Long> allFileIds = files.stream().map(FileEntity::getId).toList();

        var shareWithByFileId = appFileShareRepository.findByFileIdIn(allFileIds)
                .stream()
                .collect(Collectors.groupingBy(AppFileShare::getFileId,
                        Collectors.mapping(AppFileShare::getApiName, Collectors.toList())));

        LocalDateTime activeCutoff = LocalDateTime.now();
        var sharedWithUserCountByFileId = userFileShareRepository.findByFileIdIn(allFileIds)
                .stream()
                .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(activeCutoff))
                .collect(Collectors.groupingBy(UserFileShare::getFileId, Collectors.counting()));

        return fileDTOMapper.toFileDTO(root, childrenByParentId, shareWithByFileId, sharedWithUserCountByFileId);
    }

    /**
     * One nested {@link FileDTO} rooted at {@code id}, gated by {@link #verifyReadAccess} instead
     * of workspace-membership-only — this is what lets a person who was shared a folder (but isn't
     * a member of its workspace) browse into it in the app, not just download it as a zip. Reuses
     * the same {@code childrenByParentId} map {@link #getPersonalFolder} builds for the whole
     * workspace; the returned tree only ever follows {@code root}'s actual descendants, so a
     * caller never sees siblings or anything else in the workspace they weren't granted.
     */
    public FileDTO getSubtree(Long id) {
        FileEntity root = requireFileWithReadAccess(id, userService.getMe().id());

        Map<Long, List<FileEntity>> childrenByParentId = childrenByParentId(root.getWorkspaceId());
        return fileDTOMapper.toFileDTO(root, childrenByParentId, Map.of());
    }

    /** Total bytes currently used by the workspace (non-deleted files) against the configured
     * quota — the contract other apps/fe-keep rely on to render a usage indicator. */
    public WorkspaceUsageResponse getWorkspaceUsage(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        long usedBytes = fileRepository.sumSizeByWorkspaceIdAndDeletedAtIsNull(workspaceId);
        return WorkspaceUsageResponse.builder()
                .usedBytes(usedBytes)
                .quotaBytes(storageProperties.workspaceQuota().toBytes())
                .build();
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
        FileEntity file = requireFileWithReadAccess(id, userService.getMe().id());

        Path location = validateWithinBasePath(Path.of(file.getLocation()));

        if (file.getType() == FileType.FOLDER) {
            return downloadFolder(file, location);
        }

        if (!storageAdapter.isRegularFile(location.toString())) {
            throw new EntityNotFoundException("El archivo no existe en el disco: " + location);
        }

        // Solo en descarga/apertura real (no en el listado del árbol) — así "Recientes" refleja
        // acceso genuino y no cualquier archivo que simplemente aparece en pantalla.
        file.setLastAccessedAt(LocalDateTime.now());
        fileRepository.save(file);

        StreamingResponseBody body = out -> storageAdapter.copyFileTo(location.toString(), out);

        String contentType = file.getContentType() != null ? file.getContentType() : resolveContentType(location);

        return DownloadableFile.builder()
                .body(body)
                .filename(file.getName())
                .contentType(contentType)
                .build();
    }

    /**
     * The disk still holds a trashed file's bytes until {@link #purgeExpiredTrash()} sweeps it up
     * (or a user purges it early via {@link #purgeNode(Long)}) — the domain's source of truth for
     * "does this file still exist" is {@code deletedAt} in the DB, not disk presence. Without this,
     * zipping a folder in that window silently included anything sitting in the trash underneath
     * it. Computed from the same non-deleted pool {@link #getPersonalFolder} uses, so a trashed
     * subfolder (and everything under it) is excluded the same way it would be from the tree.
     */
    private DownloadableFile downloadFolder(FileEntity folder, Path location) {
        if (!storageAdapter.isDirectory(location.toString())) {
            throw new EntityNotFoundException("La carpeta no existe en el disco: " + location);
        }

        Set<String> includedRelativePaths = collectSubtree(
                        folder, fileRepository.findByWorkspaceIdAndDeletedAtIsNull(folder.getWorkspaceId()))
                .stream()
                .filter(node -> !node.getId().equals(folder.getId()))
                .map(node -> location.relativize(Path.of(node.getLocation())).toString().replace('\\', '/'))
                .collect(Collectors.toSet());

        StreamingResponseBody body = out -> storageAdapter.zipDirectory(location.toString(), includedRelativePaths, out);

        return DownloadableFile.builder()
                .body(body)
                .filename(folder.getName() + ".zip")
                .contentType("application/zip")
                .build();
    }

    private String resolveContentType(Path location) {
        try {
            String contentType = storageAdapter.probeContentType(location.toString());
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
            storageAdapter.deleteIfExists(path.toString());
        } catch (IOException e) {
            log.warn("No se pudo limpiar el archivo duplicado '{}'", path, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO uploadFile(Long workspaceId, Long parentId, MultipartFile file) {
        validateUploadableFile(file);

        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());
        validateWorkspaceQuota(workspaceId, file.getSize());

        FileEntity parent = resolveParent(workspaceId, parentId, owner);
        Path targetDirectory = Path.of(parent.getLocation());

        String originalFilename = Path.of(Objects.requireNonNull(file.getOriginalFilename())).getFileName().toString();
        String filename = capitalize(originalFilename);
        Path target = validateWithinBasePath(targetDirectory.resolve(filename));

        // storeNew() makes the existence check and the write a single atomic storage operation —
        // two concurrent uploads of the same name can no longer both pass a separate exists()
        // check and then race each other into overwriting one another.
        MessageDigest digest = newChecksumDigest();
        try (var input = new DigestInputStream(file.getInputStream(), digest)) {
            storageAdapter.storeNew(target.toString(), input);
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
        String searchableContent = extractSearchableContent(file, filename);

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
                .content(searchableContent)
                .location(target.toString())
                .createdAt(now)
                .updatedAt(now)
                .build();
        fileRepository.save(entity);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(workspaceId, EventType.FILE_ADDED, response));
        fileActivityLogService.record(entity.getId(), workspaceId, FileActivityAction.UPLOADED,
                entity.getName(), owner.id(), owner.email(), null);
        return response;
    }

    private static String capitalize(String filename) {
        if (filename.isEmpty()) {
            return filename;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Extracts plain-text content for search when {@code filename} is .txt/.md, {@code null}
     * otherwise. See {@link #TEXT_SEARCHABLE_EXTENSIONS} for the scope decision. */
    private String extractSearchableContent(MultipartFile file, String filename) {
        String lowerName = filename.toLowerCase(Locale.ROOT);
        boolean isTextSearchable = TEXT_SEARCHABLE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!isTextSearchable) {
            return null;
        }

        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("No se pudo extraer el contenido de texto de '{}' para búsqueda", filename, e);
            return null;
        }
    }

    private void validateUploadableFile(MultipartFile file) {
        if (file.getSize() > storageProperties.maxFileSize().toBytes()) {
            throw new BusinessException(
                    "El archivo supera el tamaño máximo permitido de " + storageProperties.maxFileSize().toMegabytes() + "MB");
        }

        String contentType = file.getContentType();
        if (contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"))) {
            throw new BusinessException("No se permite subir imágenes ni videos");
        }
    }

    private void validateWorkspaceQuota(Long workspaceId, long incomingFileSize) {
        long quotaBytes = storageProperties.workspaceQuota().toBytes();
        long currentUsage = fileRepository.sumSizeByWorkspaceIdAndDeletedAtIsNull(workspaceId);

        if (currentUsage + incomingFileSize > quotaBytes) {
            throw new BusinessException(
                    "El workspace superó la cuota de almacenamiento de " + storageProperties.workspaceQuota().toMegabytes() + "MB");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO createFolder(Long workspaceId, Long parentId, String name) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        FileEntity parent = resolveParent(workspaceId, parentId, owner);
        String folderName = Path.of(name).getFileName().toString();
        Path target = validateWithinBasePath(Path.of(parent.getLocation()).resolve(folderName));

        if (storageAdapter.exists(target.toString())) {
            throw new BusinessException("Ya existe un archivo con el nombre '" + folderName + "' en ese destino");
        }

        try {
            storageAdapter.createDirectories(target.toString());
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
        var actor = userService.getMe();
        FileEntity entity = requireFileWithWriteAccess(id, actor.id());

        if (entity.getParentId() == null) {
            throw new BusinessException("No se puede renombrar la carpeta raíz");
        }

        String oldName = entity.getName();
        String newName = Path.of(name).getFileName().toString();
        Path oldLocation = Path.of(entity.getLocation());
        Path newLocation = validateWithinBasePath(oldLocation.resolveSibling(newName));

        if (storageAdapter.exists(newLocation.toString())) {
            throw new BusinessException("Ya existe un archivo con el nombre '" + newName + "' en ese destino");
        }

        try {
            storageAdapter.move(oldLocation.toString(), newLocation.toString());
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
        fileActivityLogService.record(entity.getId(), entity.getWorkspaceId(), FileActivityAction.RENAMED,
                newName, actor.id(), actor.email(), "de '" + oldName + "'");
        return response;
    }

    /**
     * Soft-delete: moves the node (and, for a folder, its whole subtree) to the trash instead of
     * touching disk. Nothing is actually removed until {@link #purgeExpiredTrash()} sweeps it up
     * after {@value #TRASH_RETENTION_DAYS} day(s) — see {@link #restoreNode(Long)} to undo this.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long id) {
        var actor = userService.getMe();
        FileEntity entity = requireFileWithWriteAccess(id, actor.id());

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
        fileActivityLogService.record(entity.getId(), entity.getWorkspaceId(), FileActivityAction.DELETED,
                entity.getName(), actor.id(), actor.email(), null);
    }

    /** Undoes {@link #deleteNode(Long)}: clears deletedAt on the node and whatever was trashed
     * alongside it, restoring the whole subtree together. */
    @Transactional(rollbackFor = Exception.class)
    public FileDTO restoreNode(Long id) {
        var actor = userService.getMe();
        FileEntity entity = requireFileWithWriteAccess(id, actor.id());

        if (entity.getDeletedAt() == null) {
            throw new BusinessException("El archivo no está en la papelera");
        }

        List<FileEntity> subtree = collectSubtree(entity, fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(entity.getWorkspaceId()));
        subtree.forEach(node -> node.setDeletedAt(null));
        fileRepository.saveAll(subtree);

        FileDTO response = toResponseNode(entity);
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_ADDED, response));
        fileActivityLogService.record(entity.getId(), entity.getWorkspaceId(), FileActivityAction.RESTORED,
                entity.getName(), actor.id(), actor.email(), null);
        return response;
    }

    /**
     * Permanently deletes a single trashed node (and its whole subtree, for a folder) right now,
     * instead of waiting for the {@value #TRASH_RETENTION_DAYS}-day {@link #purgeExpiredTrash()}
     * sweep — previously the only ways out of the papelera were {@link #restoreNode(Long)} or that
     * automatic sweep, so there was no lever to reclaim disk space on demand. Unlike the scheduled
     * sweep, a disk failure here aborts the whole operation instead of being silently skipped and
     * retried next run — this is a foreground user action, so the caller should see the error.
     */
    @Transactional(rollbackFor = Exception.class)
    public void purgeNode(Long id) {
        var actor = userService.getMe();
        FileEntity entity = requireFileWithWriteAccess(id, actor.id());

        if (entity.getDeletedAt() == null) {
            throw new BusinessException("El archivo no está en la papelera");
        }

        List<FileEntity> subtree = collectSubtree(
                        entity, fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(entity.getWorkspaceId()))
                .stream()
                .sorted(Comparator.comparingInt((FileEntity e) -> Path.of(e.getLocation()).getNameCount()).reversed())
                .toList();

        for (FileEntity node : subtree) {
            try {
                storageAdapter.deleteIfExists(node.getLocation());
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo purgar: " + node.getLocation(), e);
            }
            fileRepository.delete(node);
        }

        FileDTO purgedNode = FileDTO.builder().id(entity.getId().toString()).name(entity.getName()).build();
        eventPublisher.publishEvent(new FileTreeChangedEvent(entity.getWorkspaceId(), EventType.FILE_DELETED, purgedNode));
    }

    public List<FileDTO> listTrash(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        return toResponseNodes(fileRepository.findByWorkspaceIdAndDeletedAtIsNotNull(workspaceId));
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
                storageAdapter.deleteIfExists(entity.getLocation());
            } catch (IOException e) {
                log.warn("No se pudo purgar '{}' del disco, se reintentará en el próximo ciclo", entity.getLocation(), e);
                continue;
            }
            fileRepository.delete(entity);
        }
    }

    /** Non-expired shares only — the count the owner's card UI shows should mean "this many
     * people can currently see it," not include grants that lapsed but weren't purged yet. */
    private int activeUserShareCount(Long fileId) {
        LocalDateTime now = LocalDateTime.now();
        return (int) userFileShareRepository.findByFileId(fileId).stream()
                .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(now))
                .count();
    }

    /** Sweeps user-file-shares whose expiration has passed — same cadence as
     * {@link #purgeExpiredTrash()}. Unlike trash there's no disk state to clean up, just DB rows,
     * so this is a straight batch delete. */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    @Transactional(rollbackFor = Exception.class)
    public void purgeExpiredUserShares() {
        List<UserFileShare> expired = userFileShareRepository.findByExpiresAtBefore(LocalDateTime.now());
        if (expired.isEmpty()) {
            return;
        }
        log.info("Purgando {} share(s) de usuario vencido(s)", expired.size());
        userFileShareRepository.deleteAll(expired);
    }

    /** Avisa un día antes de que un share por vencer expire — sin esto, ni el dueño ni el
     * destinatario se enteraban hasta que {@link #purgeExpiredUserShares()} ya había revocado el
     * acceso. Misma cadencia horaria que la purga; {@code expiryReminderSentAt} evita mandar el
     * aviso más de una vez por share aunque el job corra varias veces dentro de la ventana. */
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    @Transactional(rollbackFor = Exception.class)
    public void sendExpiringShareReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<UserFileShare> expiringSoon = userFileShareRepository
                .findByExpiresAtBetweenAndExpiryReminderSentAtIsNull(now, now.plus(EXPIRING_SHARE_REMINDER_WINDOW));
        if (expiringSoon.isEmpty()) {
            return;
        }
        log.info("Avisando {} share(s) de usuario por vencer en las próximas 24hs", expiringSoon.size());
        for (UserFileShare share : expiringSoon) {
            fileRepository.findById(share.getFileId()).ifPresent(file ->
                    eventPublisher.publishEvent(new UserFileShareEvent(
                            share.getId(), file.getId(), file.getName(), share.getSharedWithEmail(), null,
                            share.getPermission(), share.getExpiresAt(), EventType.USER_FILE_SHARE_EXPIRING)));
            share.setExpiryReminderSentAt(now);
        }
        userFileShareRepository.saveAll(expiringSoon);
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO moveNode(Long id, Long newParentId) {
        var owner = userService.getMe();
        FileEntity entity = requireFileWithWriteAccess(id, owner.id());

        if (entity.getParentId() == null) {
            throw new BusinessException("No se puede mover la carpeta raíz");
        }

        if (Objects.equals(entity.getParentId(), newParentId)) {
            return toResponseNode(entity);
        }

        Map<Long, List<FileEntity>> childrenByParentId = childrenByParentId(entity.getWorkspaceId());

        FileEntity newParent = resolveParent(entity.getWorkspaceId(), newParentId, owner);
        // resolveParent solo valida que el destino pertenezca al mismo workspace del archivo, no
        // que el caller tenga acceso a esa carpeta puntual — sin este chequeo, alguien con un
        // UserFileShare de escritura sobre un solo archivo podía reubicarlo en cualquier carpeta
        // del workspace, incluidas las que nunca le compartieron.
        verifyWriteAccess(newParent, owner.id());

        if (newParent.getId().equals(entity.getId())
                || isDescendant(entity.getId(), newParent.getId(), childrenByParentId)) {
            throw new BusinessException("No se puede mover una carpeta dentro de sí misma o de una subcarpeta suya");
        }

        Path oldLocation = Path.of(entity.getLocation());
        Path newLocation = validateWithinBasePath(Path.of(newParent.getLocation()).resolve(entity.getName()));

        if (storageAdapter.exists(newLocation.toString())) {
            throw new BusinessException(
                    "Ya existe un archivo con el nombre '" + entity.getName() + "' en ese destino");
        }

        try {
            storageAdapter.move(oldLocation.toString(), newLocation.toString());
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
        fileActivityLogService.record(entity.getId(), entity.getWorkspaceId(), FileActivityAction.MOVED,
                entity.getName(), owner.id(), owner.email(), "a '" + newParent.getName() + "'");
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
        return toResponseNode(entity, activeUserShareCount(entity.getId()));
    }

    private FileDTO toResponseNode(FileEntity entity, int sharedWithUserCount) {
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
                        .favorite(entity.isFavorite())
                        .lastAccessedAt(entity.getLastAccessedAt())
                        .folderColor(entity.getFolderColor())
                        .folderIcon(entity.getFolderIcon())
                        .sharedWithUserCount(sharedWithUserCount)
                        .build())
                .build();
    }

    /**
     * Same batching {@link #getPersonalFolder(Long)} already does for the whole tree, extracted so
     * any flat listing endpoint (favorites, recent, trash — previously each ran one
     * {@code activeUserShareCount} query per file via {@link #toResponseNode(FileEntity)}, an N+1
     * on every one of them) can map its results without a per-row round trip.
     */
    private List<FileDTO> toResponseNodes(List<FileEntity> entities) {
        List<Long> fileIds = entities.stream().map(FileEntity::getId).toList();
        LocalDateTime activeCutoff = LocalDateTime.now();
        Map<Long, Long> sharedWithUserCountByFileId = userFileShareRepository.findByFileIdIn(fileIds).stream()
                .filter(share -> share.getExpiresAt() == null || share.getExpiresAt().isAfter(activeCutoff))
                .collect(Collectors.groupingBy(UserFileShare::getFileId, Collectors.counting()));

        return entities.stream()
                .map(entity -> toResponseNode(entity,
                        sharedWithUserCountByFileId.getOrDefault(entity.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Case-insensitive name/content search scoped to a workspace — backed by an indexed SQL
     * {@code LIKE} ({@link FileRepository#searchByWorkspaceIdAndQuery}), the right tool at this
     * data scale (a personal home-lab file store) rather than an in-memory tree walk or a
     * search-engine-grade index. Content matching only ever applies to .txt/.md files (see
     * {@link #TEXT_SEARCHABLE_EXTENSIONS}); everything else is matched by name alone.
     */
    public List<FileSearchResult> searchFiles(Long workspaceId, String query) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<FileEntity> matches = fileRepository.searchByWorkspaceIdAndQuery(workspaceId, query.trim());
        Map<Long, FileEntity> byId = loadAncestors(matches);

        return matches.stream()
                .map(match -> FileSearchResult.builder()
                        .id(match.getId().toString())
                        .name(match.getName())
                        .type(match.getType())
                        .parentId(match.getParentId() != null ? match.getParentId().toString() : null)
                        .path(buildBreadcrumb(match, byId))
                        .build())
                .toList();
    }

    /**
     * Fetches only the ancestor chain of the given nodes, level by level, instead of loading the
     * whole workspace tree — bounded by tree depth rather than total file count.
     */
    private Map<Long, FileEntity> loadAncestors(List<FileEntity> nodes) {
        Map<Long, FileEntity> byId = new HashMap<>();
        Set<Long> pendingIds = nodes.stream()
                .map(FileEntity::getParentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        while (!pendingIds.isEmpty()) {
            List<FileEntity> fetched = fileRepository.findAllById(pendingIds);
            fetched.forEach(f -> byId.put(f.getId(), f));

            pendingIds = fetched.stream()
                    .map(FileEntity::getParentId)
                    .filter(Objects::nonNull)
                    .filter(id -> !byId.containsKey(id))
                    .collect(Collectors.toSet());
        }

        return byId;
    }

    private List<String> buildBreadcrumb(FileEntity node, Map<Long, FileEntity> byId) {
        List<String> ancestors = new ArrayList<>();
        Long parentId = node.getParentId();
        while (parentId != null) {
            FileEntity parent = byId.get(parentId);
            if (parent == null) {
                break;
            }
            ancestors.add(parent.getName());
            parentId = parent.getParentId();
        }
        Collections.reverse(ancestors);
        return ancestors;
    }

    @Transactional(rollbackFor = Exception.class)
    public FileDTO setFavorite(Long id, boolean favorite) {
        FileEntity entity = requireFileWithWriteAccess(id, userService.getMe().id());

        entity.setFavorite(favorite);
        entity.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(entity);

        return toResponseNode(entity);
    }

    /** Color/icon are display-only, so no validation against a palette/icon set here — that's a
     * client-side concern, same as free-form names. Folder-only: files have nothing to show them
     * on in the UI, so allowing it there would just be silently-ignored dead state. */
    @Transactional(rollbackFor = Exception.class)
    public FileDTO setFolderCustomization(Long id, String color, String icon) {
        FileEntity entity = requireFileWithWriteAccess(id, userService.getMe().id());

        if (entity.getType() != FileType.FOLDER) {
            throw new BusinessException("Solo se puede personalizar color/ícono de una carpeta");
        }

        entity.setFolderColor(color);
        entity.setFolderIcon(icon);
        entity.setUpdatedAt(LocalDateTime.now());
        fileRepository.save(entity);

        return toResponseNode(entity);
    }

    public List<FileDTO> listFavorites(Long workspaceId) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        return toResponseNodes(fileRepository.findByWorkspaceIdAndDeletedAtIsNullAndFavoriteTrue(workspaceId));
    }

    /** Files ordered by most-recently-accessed first, excluding anything never actually opened
     * (see {@link #downloadFile(Long)} for where lastAccessedAt is set). */
    public List<FileDTO> listRecent(Long workspaceId, Integer limit) {
        var owner = userService.getMe();
        workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, owner.id());

        Pageable pageable = PageRequest.of(0, limit != null && limit > 0 ? limit : DEFAULT_RECENT_LIMIT);
        return toResponseNodes(fileRepository
                .findByWorkspaceIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(workspaceId, pageable));
    }

    /** Files/folders another person shared with the calling user, excluding anything expired —
     * this is how a recipient discovers shared content in the first place, since they're not a
     * workspace member and can't see the normal tree. Mirrors {@link #listFavorites(Long)}'s
     * shape (a flat list via {@link #toResponseNode}), just scoped by grant instead of workspace. */
    public List<FileDTO> listSharedWithMe() {
        Long userId = userService.getMe().id();
        List<UserFileShare> activeShares = userFileShareRepository.findActiveBySharedWithUserId(userId, LocalDateTime.now());

        return activeShares.stream()
                .map(share -> fileRepository.findById(share.getFileId()).orElse(null))
                .filter(Objects::nonNull)
                .map(this::toResponseNode)
                .toList();
    }

    /** Timeline for one file/folder — who uploaded, renamed, moved, deleted, restored, or
     * (un)shared it, most recent first. Gated the same as viewing the file itself: activity
     * reveals who has interacted with it, so it shouldn't be visible to anyone who couldn't
     * already read the file. */
    public List<FileActivityResponse> getActivity(Long id) {
        requireFileWithReadAccess(id, userService.getMe().id());

        return fileActivityRepository.findByFileIdOrderByCreatedAtDesc(id).stream()
                .map(fileActivityMapper::toResponse)
                .toList();
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
