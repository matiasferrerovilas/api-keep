package api.m2.file.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves which app a request actually came from, using the JWT's {@code app} claim — set
 * per-client in Keycloak via a hardcoded-claim protocol mapper, so it's covered by the token
 * signature and can't be forged by whichever backend is handling the request. Used to decide
 * whether a caller outside the file's own workspace can still reach it through an
 * {@code AppFileShare} grant.
 */
@Component
public class SourceAppResolver {

    private static final String APP_CLAIM = "app";

    public Optional<String> resolveCallingApp() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(auth -> auth.getToken().getClaimAsString(APP_CLAIM));
    }
}
