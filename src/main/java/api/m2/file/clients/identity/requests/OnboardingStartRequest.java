package api.m2.file.clients.identity.requests;

import java.util.List;

public record OnboardingStartRequest(UserToAdd user, List<AddWorkspaceRecord> workspaces) {
}
