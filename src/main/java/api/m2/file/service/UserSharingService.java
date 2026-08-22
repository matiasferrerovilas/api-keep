package api.m2.file.service;

import api.m2.file.entity.FileEntity;
import api.m2.file.entity.UserFileShare;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.mappers.UserFileShareMapper;
import api.m2.file.record.CreateUserFileShareRequest;
import api.m2.file.record.UserFileShareResponse;
import api.m2.file.repository.FileRepository;
import api.m2.file.repository.UserFileShareRepository;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** CRUD for {@link UserFileShare} — sharing a file/folder with a specific person, as opposed to
 * {@link SharingService}, which shares with another app in the suite. */
@Service
@RequiredArgsConstructor
public class UserSharingService {

    private final UserFileShareRepository userFileShareRepository;
    private final FileRepository fileRepository;
    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final UserFileShareMapper userFileShareMapper;
    private final FileActivityLogService fileActivityLogService;

    @Transactional(rollbackFor = Exception.class)
    public UserFileShareResponse shareWithUser(CreateUserFileShareRequest request) {
        var owner = userService.getMe();
        FileEntity file = fileRepository.findById(request.fileId())
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + request.fileId()));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), owner.id());

        var targetUser = userService.lookupUserByEmail(request.email());

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

        return userFileShareMapper.toResponse(share);
    }

    public List<UserFileShareResponse> getShares(Long fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + fileId));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), userService.getMe().id());

        return userFileShareRepository.findByFileId(fileId).stream()
                .map(userFileShareMapper::toResponse)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(Long shareId) {
        var actor = userService.getMe();
        UserFileShare share = userFileShareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el share con id " + shareId));

        FileEntity file = fileRepository.findById(share.getFileId())
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + share.getFileId()));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), actor.id());

        userFileShareRepository.delete(share);
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.UNSHARED,
                file.getName(), actor.id(), actor.email(), "con '" + share.getSharedWithEmail() + "'");
    }
}
