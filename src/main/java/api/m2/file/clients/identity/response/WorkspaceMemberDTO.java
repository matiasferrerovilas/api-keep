package api.m2.file.clients.identity.response;

import api.m2.file.enums.WorkspaceRole;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public record WorkspaceMemberDTO(
        Long id,
        Long workspaceId,
        String workspaceName,
        Metadata metadata) implements Serializable {

    public record Metadata(
            List<String> members,
            List<MemberDetail> memberDetails,
            WorkspaceRole role,
            LocalDateTime joinedAt,
            Boolean isDefault) implements Serializable {
    }

    public record MemberDetail(Long userId, String email, WorkspaceRole role) implements Serializable {
    }
}
