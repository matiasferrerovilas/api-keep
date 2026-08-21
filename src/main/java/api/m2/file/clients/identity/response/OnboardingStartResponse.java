package api.m2.file.clients.identity.response;

import java.util.List;

public record OnboardingStartResponse(UserMe user, List<WorkspaceAdded> workspaces) {
}
