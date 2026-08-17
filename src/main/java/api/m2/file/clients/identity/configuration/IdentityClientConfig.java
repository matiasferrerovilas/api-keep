package api.m2.file.clients.identity.configuration;

import api.m2.file.clients.identity.IdentityClient;
import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.exceptions.ErrorResponse;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.exceptions.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Configuration
public class IdentityClientConfig {

    // No X-Source-Service header: api-identity now derives the calling app from the forwarded
    // JWT's app claim (set per-client in Keycloak via a hardcoded-claim mapper), which can't be spoofed by this
    // backend the way a plain header could.
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNKNOWN_ERROR_DETAIL = "Error desconocido al comunicarse con api-identity";
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409;

    @Bean
    public IdentityClient identityClient(@Value("${identity.base-url}") String baseUrl, JsonMapper jsonMapper) {
        var restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().add(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + this.getCurrentToken());
                    return execution.execute(request, body);
                })
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) ->
                        this.handleErrorResponse(response, jsonMapper))
                .build();
        var adapter = RestClientAdapter.create(restClient);
        var factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(IdentityClient.class);
    }

    private String getCurrentToken() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }

        throw new PermissionDeniedException("Usuario no autenticado");
    }

    private void handleErrorResponse(ClientHttpResponse response, JsonMapper jsonMapper) throws IOException {
        String detail = this.extractDetail(response, jsonMapper);
        int statusCode = response.getStatusCode().value();

        throw switch (statusCode) {
            case HTTP_CONFLICT -> new EntityAlreadyExistsException(detail);
            case HTTP_NOT_FOUND -> new EntityNotFoundException(detail);
            case HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> new PermissionDeniedException(detail);
            case HTTP_BAD_REQUEST -> new BusinessException(detail);
            default -> new ServiceException("Error al comunicarse con api-identity: " + detail);
        };
    }

    private String extractDetail(ClientHttpResponse response, JsonMapper jsonMapper) {
        try (var body = response.getBody()) {
            return jsonMapper.readValue(body, ErrorResponse.class).detail();
        } catch (IOException | JacksonException e) {
            return UNKNOWN_ERROR_DETAIL;
        }
    }
}
