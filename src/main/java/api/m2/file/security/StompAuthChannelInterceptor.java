package api.m2.file.security;

import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.service.UserService;
import api.m2.file.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /ws/**} was {@code permitAll()} at the HTTP layer (necessary — SockJS's handshake/XHR
 * fallback requests aren't the STOMP CONNECT frame itself) but nothing validated the STOMP frames
 * flowing over the resulting session: any client, authenticated or not, could open the SockJS
 * connection and SUBSCRIBE to any topic — including ones addressed by workspace id or by another
 * user's email — and passively collect file names, shares and invitations from any workspace.
 * This closes that the same way HTTP requests are already closed: CONNECT requires a valid JWT
 * (same {@link JwtDecoder}/{@link JwtAuthenticationConverter} beans the HTTP filter chain uses),
 * and every SUBSCRIBE is checked against the connected user before it's allowed through — a
 * workspace-scoped topic requires membership (verified against api-identity, same as any HTTP
 * endpoint would), an email-scoped topic requires the destination email to match the caller's own.
 * A destination that matches none of the known topic shapes is rejected by default rather than
 * let through — this is meant to be the complete list of what the frontends ever subscribe to.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String USER_ID_SESSION_ATTR = "userId";

    // (?i) porque los emails direccionan topics de invitaciones/shares con el casing que haya
    // usado el invitador al escribirlos, y el resto del backend ya compara emails sin distinguir
    // mayúsculas/minúsculas (ver UserAddService).
    private static final List<Pattern> WORKSPACE_SCOPED_TOPICS = List.of(
            Pattern.compile("^/topic/files/(\\d+)/(new|update|delete)$"),
            Pattern.compile("^/topic/workspace/(\\d+)/members/update$")
    );
    private static final List<Pattern> EMAIL_SCOPED_TOPICS = List.of(
            Pattern.compile("^/topic/invitations/([^/]+)/new$"),
            Pattern.compile("^/topic/membership/([^/]+)/remove$"),
            Pattern.compile("^/topic/shares/users/([^/]+)/new$")
    );

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final UserService userService;
    private final WorkspaceService workspaceService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // getAccessor (not wrap()) retrieves the same mutable accessor instance stored on the
        // message when the STOMP session built it with leaveMutable(true) — mutations here
        // (setUser, session attributes) apply in place, so returning `message` unchanged still
        // reflects them. wrap() would instead hand back a fresh, disconnected copy.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            log.warn("Conexión WebSocket rechazada: sin header Authorization");
            throw new MessagingException("Falta autenticación");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(authHeader.substring(7));
        } catch (JwtException e) {
            log.warn("Conexión WebSocket rechazada: token inválido");
            throw new MessagingException("Token inválido", e);
        }

        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt);
        accessor.setUser(authentication);

        // Se resuelve el userId numérico UNA sola vez, al conectar, y se guarda en la sesión STOMP
        // para no repetir el round-trip a api-identity en cada SUBSCRIBE de esta misma conexión.
        withAuthentication(authentication, () -> {
            Long userId = userService.getMe().id();
            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes != null) {
                sessionAttributes.put(USER_ID_SESSION_ATTR, userId);
            }
        });
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Authentication user = (Authentication) accessor.getUser();
        String destination = accessor.getDestination();

        if (user == null || destination == null) {
            log.warn("SUBSCRIBE rechazado: sesión no autenticada o sin destino");
            throw new MessagingException("No autorizado");
        }

        String workspaceMatch = firstGroupMatch(WORKSPACE_SCOPED_TOPICS, destination);
        if (workspaceMatch != null) {
            authorizeWorkspaceTopic(accessor, user, destination, Long.parseLong(workspaceMatch));
            return;
        }

        String emailMatch = firstGroupMatch(EMAIL_SCOPED_TOPICS, destination);
        if (emailMatch != null) {
            if (!emailMatch.equalsIgnoreCase(user.getName())) {
                log.warn("SUBSCRIBE rechazado: '{}' intentó suscribirse a un topic de otro usuario ({})",
                        user.getName(), destination);
                throw new MessagingException("No autorizado");
            }
            return;
        }

        log.warn("SUBSCRIBE rechazado: destino desconocido '{}'", destination);
        throw new MessagingException("Destino no reconocido");
    }

    private void authorizeWorkspaceTopic(StompHeaderAccessor accessor, Authentication user, String destination, Long workspaceId) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Long userId = sessionAttributes != null ? (Long) sessionAttributes.get(USER_ID_SESSION_ATTR) : null;
        if (userId == null) {
            log.warn("SUBSCRIBE rechazado: sesión sin userId resuelto");
            throw new MessagingException("No autorizado");
        }

        withAuthentication(user, () -> {
            try {
                workspaceService.verifyUserIsMemberOfWorkspace(workspaceId, userId);
            } catch (PermissionDeniedException e) {
                log.warn("SUBSCRIBE rechazado: '{}' no pertenece al workspace {} ({})", user.getName(), workspaceId, destination);
                throw new MessagingException("No autorizado", e);
            }
        });
    }

    /** Corre {@code action} con {@code authentication} puesto en el {@link SecurityContextHolder}
     * del hilo actual — {@code IdentityClientConfig} (y por extensión {@code UserService}/
     * {@code WorkspaceService}) lo lee de ahí para el header saliente hacia api-identity, igual
     * que en un request HTTP normal, pero el procesamiento de frames STOMP no pasa por el filtro
     * de seguridad HTTP que normalmente lo puebla. */
    private void withAuthentication(Authentication authentication, Runnable action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            action.run();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    @Nullable
    private String firstGroupMatch(List<Pattern> patterns, String destination) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(destination);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}
