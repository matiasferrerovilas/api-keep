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
import api.m2.file.repository.FileRepository;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SharingService {

    private final AppFileShareRepository fileShareRepository;
    private final FileRepository fileRepository;
    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final AppFileShareMapper appFileShareMapper;
    private final FileShareEventPublisher fileShareEventPublisher;
    private final FileActivityLogService fileActivityLogService;

    @Transactional(rollbackFor = Exception.class)
    public FileShareResponse shareFile(CreateFileShareRequest request) {
        var owner = userService.getMe();
        FileEntity file = fileRepository.findById(request.fileId())
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + request.fileId()));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), owner.id());

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

    public List<FileShareResponse> getShares(Long fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + fileId));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), userService.getMe().id());

        return fileShareRepository.findByFileId(fileId).stream()
                .map(appFileShareMapper::toResponse)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeShare(Long shareId) {
        var actor = userService.getMe();
        AppFileShare share = fileShareRepository.findById(shareId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el share con id " + shareId));

        FileEntity file = fileRepository.findById(share.getFileId())
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + share.getFileId()));

        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), actor.id());

        fileShareRepository.delete(share);
        fileActivityLogService.record(file.getId(), file.getWorkspaceId(), FileActivityAction.UNSHARED,
                file.getName(), actor.id(), actor.email(), "con la api '" + share.getApiName() + "'");
    }
}
