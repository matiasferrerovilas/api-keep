package api.m2.file.service;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.clients.identity.requests.UserToAdd;
import api.m2.file.clients.identity.response.UserLookupResponse;
import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.enums.UserType;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.exceptions.PermissionDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final IdentityClient identityClient;
    private static final String EMAIL_CLAIM = "email";
    private static final String GIVEN_NAME_CLAIM = "given_name";
    private static final String FAMILY_NAME_CLAIM = "family_name";

    @Transactional
    public UserMe getMe() {
        return identityClient.getMe();
    }

    /** Resolves an email to an existing user, for creating a user-to-user file share — fails
     * loudly (unlike the workspace-invitation flow, which silently drops unmatched emails) so the
     * sharer gets an honest "no account with that email" instead of a share that goes nowhere. */
    public UserLookupResponse lookupUserByEmail(String email) {
        try {
            return identityClient.lookupUser(email);
        } catch (RestClientResponseException e) {
            throw new EntityNotFoundException("No existe ningún usuario con el email '" + email + "'");
        }
    }

    /**
     * Arma el payload de alta de usuario a partir del JWT autenticado, sin llamar todavía a
     * identity — lo usa {@code OnboardingService.finish()} para combinarlo con la creación de
     * workspaces en una sola llamada atómica ({@link IdentityClient#startOnboarding}), o para
     * crear solo el usuario cuando el onboarding no crea workspaces nuevos (se une a uno existente).
     */
    public UserToAdd buildUserToAdd() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        if (!(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwtAuth)) {
            throw new PermissionDeniedException("Usuario no autenticado");
        }

        var jwt = jwtAuth.getToken();
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        String givenName = jwt.getClaimAsString(GIVEN_NAME_CLAIM);
        String familyName = jwt.getClaimAsString(FAMILY_NAME_CLAIM);

        return UserToAdd.builder()
                .email(email)
                .givenName(givenName)
                .familyName(familyName)
                .isFirstLogin(true)
                .userType(UserType.PERSONAL)
                .build();
    }
}
