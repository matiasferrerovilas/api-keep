package api.m2.file.service;

import api.m2.file.entity.FileEntity;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.repository.FileRepository;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Looks up a file by id (404 if missing) and verifies the caller belongs to its workspace — the
 * "find the file, then check membership" pair that used to be copy-pasted at every call site in
 * {@link SharingService} and {@link UserSharingService}. Managing app/user shares is
 * membership-gated only (on top of the {@code @PreAuthorize("hasRole('ADMIN')")} on every method
 * that uses this), unlike {@code FileService}'s own access checks, which additionally fall back to
 * an app/user share grant for callers who aren't native workspace members — that fuller check
 * doesn't apply here, since sharing is itself an admin/member-only action.
 */
@Component
@RequiredArgsConstructor
public class FileMembershipGuard {

    private final FileRepository fileRepository;
    private final WorkspaceService workspaceService;

    public FileEntity requireFileWithMembership(Long fileId, Long userId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new EntityNotFoundException("No se encontró el archivo con id " + fileId));
        workspaceService.verifyUserIsMemberOfWorkspace(file.getWorkspaceId(), userId);
        return file;
    }
}
