package api.m2.file.unit.security;

import api.m2.file.clients.identity.response.UserMe;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.security.JwtAuthenticationConverter;
import api.m2.file.security.StompAuthChannelInterceptor;
import api.m2.file.service.UserService;
import api.m2.file.service.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Covers the STOMP-level auth gate: CONNECT requires a valid Bearer JWT, and every SUBSCRIBE is
 * checked against the connected user — workspace-scoped topics need membership, email-scoped
 * topics need the destination email to match the caller's own, and anything unrecognized is
 * rejected rather than let through.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    JwtDecoder jwtDecoder;
    @Mock
    UserService userService;
    @Mock
    WorkspaceService workspaceService;

    StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(
                jwtDecoder, new JwtAuthenticationConverter(), userService, workspaceService);
    }

    private Jwt jwtFor(String email) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("email", email)
                .claim("sub", email)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private Message<byte[]> connectMessage(String authHeader, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, AbstractAuthenticationToken user, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(user);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connect_rejectsWhenThereIsNoAuthorizationHeader() {
        Message<byte[]> message = connectMessage(null, new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connect_rejectsAnInvalidToken() {
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("invalid"));
        Message<byte[]> message = connectMessage("Bearer bad-token", new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void connect_setsTheUserAndResolvesUserIdIntoTheSession() {
        when(jwtDecoder.decode("good-token")).thenReturn(jwtFor("user@example.com"));
        when(userService.getMe()).thenReturn(new UserMe(1L, "user@example.com", "N", "A", "PERSONAL", null));
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<byte[]> message = connectMessage("Bearer good-token", sessionAttributes);

        Message<?> result = interceptor.preSend(message, null);

        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getUser()).isNotNull();
        assertThat(resultAccessor.getUser().getName()).isEqualTo("user@example.com");
        assertThat(sessionAttributes.get("userId")).isEqualTo(1L);
    }

    @Test
    void subscribe_rejectsWhenThereIsNoAuthenticatedUser() {
        Message<byte[]> message = subscribeMessage("/topic/files/5/new", null, new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_allowsAWorkspaceScopedTopicWhenTheCallerIsAMember() {
        var user = new JwtAuthenticationToken(jwtFor("user@example.com"), List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        Map<String, Object> sessionAttributes = new HashMap<>(Map.of("userId", 1L));
        doNothing().when(workspaceService).verifyUserIsMemberOfWorkspace(5L, 1L);
        Message<byte[]> message = subscribeMessage("/topic/files/5/new", user, sessionAttributes);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isNotNull();
    }

    @Test
    void subscribe_rejectsAWorkspaceScopedTopicWhenTheCallerIsNotAMember() {
        var user = new JwtAuthenticationToken(jwtFor("user@example.com"), List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        Map<String, Object> sessionAttributes = new HashMap<>(Map.of("userId", 1L));
        doThrow(new PermissionDeniedException("no")).when(workspaceService).verifyUserIsMemberOfWorkspace(anyLong(), anyLong());
        Message<byte[]> message = subscribeMessage("/topic/files/99/new", user, sessionAttributes);

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_rejectsAWorkspaceScopedTopicWhenTheSessionHasNoResolvedUserId() {
        var user = new JwtAuthenticationToken(jwtFor("user@example.com"), List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        Message<byte[]> message = subscribeMessage("/topic/files/5/new", user, new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_allowsAnEmailScopedTopicWhenItMatchesTheCallersOwnEmail() {
        var user = new JwtAuthenticationToken(jwtFor("user@example.com"), List.of());
        Message<byte[]> message = subscribeMessage("/topic/invitations/user@example.com/new", user, new HashMap<>());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isNotNull();
    }

    @Test
    void subscribe_allowsAnEmailScopedTopicRegardlessOfCasing() {
        var user = new JwtAuthenticationToken(jwtFor("USER@example.com"), List.of());
        Message<byte[]> message = subscribeMessage("/topic/shares/users/user@EXAMPLE.com/new", user, new HashMap<>());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isNotNull();
    }

    @Test
    void subscribe_rejectsAnEmailScopedTopicBelongingToSomeoneElse() {
        var user = new JwtAuthenticationToken(jwtFor("attacker@example.com"), List.of());
        Message<byte[]> message = subscribeMessage("/topic/membership/victim@example.com/remove", user, new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void subscribe_rejectsAnUnrecognizedDestinationByDefault() {
        var user = new JwtAuthenticationToken(jwtFor("user@example.com"), List.of());
        Message<byte[]> message = subscribeMessage("/topic/something/made/up", user, new HashMap<>());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void otherStompCommandsPassThroughUnchecked() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/anything");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
    }
}
