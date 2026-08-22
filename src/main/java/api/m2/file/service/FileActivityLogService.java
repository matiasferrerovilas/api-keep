package api.m2.file.service;

import api.m2.file.entity.FileActivity;
import api.m2.file.enums.FileActivityAction;
import api.m2.file.repository.FileActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Write path for the file/folder activity log — kept as its own tiny service (rather than a
 * method on FileService) since SharingService/UserSharingService also need to record SHARED/
 * UNSHARED without depending on the whole of FileService. */
@Service
@RequiredArgsConstructor
public class FileActivityLogService {

    private final FileActivityRepository fileActivityRepository;

    public void record(Long fileId, Long workspaceId, FileActivityAction action, String fileName,
                        Long actorUserId, String actorEmail, String detail) {
        fileActivityRepository.save(FileActivity.builder()
                .fileId(fileId)
                .workspaceId(workspaceId)
                .action(action)
                .fileName(fileName)
                .actorUserId(actorUserId)
                .actorEmail(actorEmail)
                .detail(detail)
                .build());
    }
}
