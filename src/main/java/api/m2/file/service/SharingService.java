package api.m2.file.service;

import api.m2.file.entity.AppFileShare;
import api.m2.file.entity.FileEntity;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.events.FileSharedEvent;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.AppFileShareMapper;
import api.m2.file.record.CreateFileShareRequest;
import api.m2.file.record.FileShareResponse;
import api.m2.file.repository.AppFileShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Compartir (con otra app o con otra persona) es una acción de administración del workspace —
 * gestionado por {@code ROLE_ADMIN} únicamente, mismo criterio que ya aplicaba el frontend
 * (fe-keep ocultaba el menú "Compartir" a no-admins) pero que el backend nunca verificaba: un
 * FAMILY podía llamar estos endpoints directo. Nótese que esto NO cubre leer contenido ya
 * compartido con vos ({@code getSharedWithMe}/subtree en FileService) — eso sigue abierto a
 * cualquier miembro, si no compartir con un FAMILY sería inútil. */
@Service
@RequiredArgsConstructor
public class SharingService {

    private final AppFileShareRepository fileShareRepository;
    private final FileMembershipGuard fileMembershipGuard;
    private final UserService userService;
    private final AppFileShareMapper appFileShareMapper;
    private final FileShareEventPublisher fileShareEventPublisher;
    private final FileActivityLogService fileActivityLogService;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = Exception.class)
    public FileShareResponse shareFile(CreateFileShareRequest request) {
        var owner = userService.getMe();
        FileEntity file = fileMembershipGuard.requireFileWithMembership(request.fileId(), owner.id());

        if (fileShareRepository.existsByFileIdAndApiName(request.fileId(), request.apiName())) {
            throw new EntityAlreadyExistsException(
                    "El archivo ya está compartido con la api '" + request.apiName() + "'");
        }

        AppFileShare share = AppFileShare.builder()
                .fileId(file.getId())
                .apiName(request.apiName())
                .permission(request.permission())
                .createdBy(owner.id())
                .build();

        fileShareRepository.save(share);

        fileShareEventPublisher.publishFileShared(new FileSharedEvent(
                file.getId(), file.getName(), share.getApiName(), share.getPermission(), owner.id()));
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.SHARED,
                file.getName(), owner.id(), owner.email(), "con la api '" + share.getApiName() + "'");

        return appFileShareMapper.toResponse(share);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<FileShareResponse> getShares(Long fileId) {
        fileMembershipGuard.requireFileWithMembership(fileId, userService.getMe().id());

        return fileShareRepository.findByFileId(fileId).stream()
                .map(appFileShareMapper::toResponse)
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(Long shareId) {
        var actor = userService.getMe();
        AppFileShare share = fileShareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el share con id " + shareId));

        FileEntity file = fileMembershipGuard.requireFileWithMembership(share.getFileId(), actor.id());

        fileShareRepository.delete(share);
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.UNSHARED,
                file.getName(), actor.id(), actor.email(), "con la api '" + share.getApiName() + "'");
    }
}
