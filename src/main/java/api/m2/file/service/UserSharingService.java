package api.m2.file.service;

import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.EventType;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.UserFileShareMapper;
import api.m2.file.record.CreateUserFileShareRequest;
import api.m2.file.record.UpdateUserFileShareRequest;
import api.m2.file.record.UserFileShareResponse;
import api.m2.file.record.events.UserFileShareEvent;
import api.m2.file.repository.UserFileShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** CRUD for {@link UserFileShare} — sharing a file/folder with a specific person, as opposed to
 * {@link SharingService}, which shares with another app in the suite. Managing shares (create/
 * list-grantees/update/revoke) is {@code ROLE_ADMIN}-only, matching fe-keep's UI gate — this does
 * NOT restrict {@code FileService.listSharedWithMe}/{@code getSubtree}, which is how a recipient
 * (of any role) reads content already shared with them. */
@Service
@RequiredArgsConstructor
public class UserSharingService {

    private final UserFileShareRepository userFileShareRepository;
    private final FileMembershipGuard fileMembershipGuard;
    private final UserService userService;
    private final UserFileShareMapper userFileShareMapper;
    private final FileActivityLogService fileActivityLogService;
    private final ApplicationEventPublisher eventPublisher;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = Exception.class)
    public UserFileShareResponse shareWithUser(CreateUserFileShareRequest request) {
        var owner = userService.getMe();
        FileEntity file = fileMembershipGuard.requireFileWithMembership(request.fileId(), owner.id());

        var targetUser = userService.lookupUserByEmail(request.email());

        if (targetUser.id().equals(owner.id())) {
            throw new BusinessException("No podés compartir un archivo con vos mismo");
        }

        if (userFileShareRepository.existsByFileIdAndSharedWithUserId(request.fileId(), targetUser.id())) {
            throw new EntityAlreadyExistsException(
                    "El archivo ya está compartido con '" + request.email() + "'");
        }

        UserFileShare share = UserFileShare.builder()
                .fileId(file.getId())
                .sharedWithUserId(targetUser.id())
                .sharedWithEmail(targetUser.email())
                .permission(request.permission())
                .expiresAt(request.expiresAt())
                .createdBy(owner.id())
                .build();

        userFileShareRepository.save(share);
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.SHARED,
                file.getName(), owner.id(), owner.email(), "con '" + targetUser.email() + "'");
        eventPublisher.publishEvent(new UserFileShareEvent(
                share.getId(), file.getId(), file.getName(), targetUser.email(), owner.email(),
                share.getPermission(), share.getExpiresAt(), EventType.USER_FILE_SHARED));

        return userFileShareMapper.toResponse(share);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = Exception.class)
    public UserFileShareResponse updateShare(Long shareId, UpdateUserFileShareRequest request) {
        UserFileShare share = userFileShareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el share con id " + shareId));

        fileMembershipGuard.requireFileWithMembership(share.getFileId(), userService.getMe().id());

        share.setPermission(request.permission());
        if (!Objects.equals(share.getExpiresAt(), request.expiresAt())) {
            // El vencimiento cambió — si ya se había mandado el aviso de "por vencer" para la
            // fecha vieja, hay que poder mandarlo de nuevo para la nueva.
            share.setExpiryReminderSentAt(null);
        }
        share.setExpiresAt(request.expiresAt());
        userFileShareRepository.save(share);

        return userFileShareMapper.toResponse(share);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<UserFileShareResponse> getShares(Long fileId) {
        fileMembershipGuard.requireFileWithMembership(fileId, userService.getMe().id());

        return userFileShareRepository.findByFileId(fileId).stream()
                .map(userFileShareMapper::toResponse)
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(Long shareId) {
        var actor = userService.getMe();
        UserFileShare share = userFileShareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el share con id " + shareId));

        FileEntity file = fileMembershipGuard.requireFileWithMembership(share.getFileId(), actor.id());

        userFileShareRepository.delete(share);
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.UNSHARED,
                file.getName(), actor.id(), actor.email(), "con '" + share.getSharedWithEmail() + "'");
    }
}
