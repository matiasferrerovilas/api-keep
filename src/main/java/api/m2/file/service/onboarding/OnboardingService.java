package api.m2.file.service.onboarding;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.clients.identity.requests.AddWorkspaceRecord;
import api.m2.file.clients.identity.requests.OnboardingStartRequest;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.clients.identity.response.WorkspaceAdded;
import api.m2.file.enums.UserSettingKey;
import api.m2.file.record.onboarding.OnBoardingForm;
import api.m2.file.service.FileService;
import api.m2.file.service.UserService;
import api.m2.file.service.settings.UserSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingService {
    private static final String DEFAULT_WORKSPACE_NAME = "DEFAULT";

    private final IdentityClient identityClient;
    private final FileService fileService;
    private final UserService userService;
    private final UserSettingService userSettingService;

    @Transactional(rollbackFor = Exception.class)
    public void finish(@Valid OnBoardingForm onBoardingForm) {
        var userToAdd = userService.buildUserToAdd();

        List<String> namesToCreate = onBoardingForm.workspacesToAdd() == null
                ? List.of()
                : onBoardingForm.workspacesToAdd().stream().filter(Objects::nonNull).toList();
        boolean needsNewWorkspace = !namesToCreate.isEmpty() || onBoardingForm.existingDefaultWorkspaceId() == null;

        UserMe owner;
        List<WorkspaceAdded> created;
        if (needsNewWorkspace) {
            var workspacesToAdd = (namesToCreate.isEmpty() ? List.of(DEFAULT_WORKSPACE_NAME) : namesToCreate)
                    .stream().map(AddWorkspaceRecord::new).toList();
            var result = identityClient.startOnboarding(new OnboardingStartRequest(userToAdd, workspacesToAdd));
            owner = result.user();
            created = result.workspaces();
        } else {
            owner = identityClient.createLogInUser(userToAdd);
            created = List.of();
        }

        Long defaultWorkspaceId = onBoardingForm.existingDefaultWorkspaceId() != null
                ? onBoardingForm.existingDefaultWorkspaceId()
                : created.getFirst().id();

        userSettingService.upsertForUser(owner.id(), UserSettingKey.DEFAULT_WORKSPACE, defaultWorkspaceId);

        fileService.getPersonalFolder(defaultWorkspaceId);
        if (onBoardingForm.filesToAdd() != null) {
            onBoardingForm.filesToAdd().forEach(file -> fileService.uploadFile(defaultWorkspaceId, null, file));
        }

        identityClient.changeUserFirstLoginStatus(owner.id());
    }

    public void markTourAsSeen() {
        identityClient.markTourAsSeen();
    }
}
