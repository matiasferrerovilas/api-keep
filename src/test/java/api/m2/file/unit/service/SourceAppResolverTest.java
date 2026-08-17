package api.m2.file.unit.service;

import api.m2.file.service.SourceAppResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAppResolverTest {

    private final SourceAppResolver resolver = new SourceAppResolver();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveCallingApp_returnsTheAppClaimFromTheToken() {
        authenticateWithApp("api-movements");

        assertThat(resolver.resolveCallingApp()).contains("api-movements");
    }

    @Test
    void resolveCallingApp_isEmptyWhenTheAppClaimIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("azp", "some-client-without-the-mapper-configured")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(resolver.resolveCallingApp()).isEmpty();
    }

    @Test
    void resolveCallingApp_isEmptyWhenThereIsNoAuthentication() {
        assertThat(resolver.resolveCallingApp()).isEmpty();
    }

    private static void authenticateWithApp(String app) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("app", app)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
