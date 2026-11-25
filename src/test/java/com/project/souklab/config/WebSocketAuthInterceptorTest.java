package com.project.souklab.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.project.souklab.access.Capability;
import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.access.ViewerContext;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.User;
import com.project.souklab.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {
    @Mock JwtUtils jwtUtils;
    @Mock UserDetailsService userDetailsService;
    @Mock UserDetails userDetails;
    @Mock WebSocketSessionRegistry sessionRegistry;
    @Mock UserRepository userRepository;
    @Mock CapabilityService capabilityService;

    @BeforeEach
    void setUp() {
        User client = User.builder().email("client@example.com").accountRole(AccountRole.CLIENT).build();
        client.setId("client-id");
        lenient().when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(client));
        lenient().when(capabilityService.compute(eq(client), any(Instant.class)))
                .thenReturn(new ViewerContext(client.getId(), AccountRole.CLIENT, PremiumStatus.none(),
                        Set.of(Capability.SEND_MESSAGE, Capability.START_CONVERSATION), false));
    }

    @Test
    void preSend_returnsNonConnectMessagesUnchanged() {
        WebSocketAuthInterceptor interceptor = interceptor();
        Message<String> message = MessageBuilder.withPayload("payload").build();

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verifyNoInteractions(jwtUtils, userDetailsService, sessionRegistry);
    }

    @Test
    void preSend_authenticatesValidBearerTokenAndSetsPrincipal() {
        when(jwtUtils.validateJwtToken("token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("token")).thenReturn("client@example.com");
        when(userDetailsService.loadUserByUsername("client@example.com")).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("client@example.com");
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(true);

        Message<?> result = interceptor().preSend(connect("Bearer token"), null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("client@example.com");
        assertThat(((Authentication) accessor.getUser()).getAuthorities())
                .isEqualTo(userDetails.getAuthorities());
    }

    @Test
    void preSend_rejectsMissingInvalidAndUnusableCredentials() {
        WebSocketAuthInterceptor interceptor = interceptor();

        assertThatThrownBy(() -> interceptor.preSend(connect(null), null))
                .isInstanceOf(AccessDeniedException.class);

        when(jwtUtils.validateJwtToken("bad-token")).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(connect("bad-token"), null))
                .isInstanceOf(AccessDeniedException.class);

        when(jwtUtils.validateJwtToken("disabled-token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("disabled-token")).thenReturn("disabled@example.com");
        when(userDetailsService.loadUserByUsername("disabled@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer disabled-token"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_rejectsEmptyHeaderAndLockedOrInvalidTokenFailures() throws Exception {
        WebSocketAuthInterceptor interceptor = interceptor();
        StompHeaderAccessor emptyHeaders = StompHeaderAccessor.create(StompCommand.CONNECT);
        emptyHeaders.addNativeHeader("Authorization", "placeholder");
        var nativeHeaders = StompHeaderAccessor.class.getDeclaredMethod("getNativeHeaders");
        nativeHeaders.setAccessible(true);
        @SuppressWarnings("unchecked")
        var headers = (Map<String, List<String>>) nativeHeaders.invoke(emptyHeaders);
        headers.put("Authorization", List.of());
        emptyHeaders.setLeaveMutable(true);
        Message<byte[]> emptyHeaderMessage = MessageBuilder.createMessage(new byte[0], emptyHeaders.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(emptyHeaderMessage, null))
                .isInstanceOf(AccessDeniedException.class);

        when(jwtUtils.validateJwtToken("locked-token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("locked-token")).thenReturn("locked@example.com");
        when(userDetailsService.loadUserByUsername("locked@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer locked-token"), null))
                .isInstanceOf(AccessDeniedException.class);

        when(jwtUtils.validateJwtToken("error-token")).thenThrow(new IllegalArgumentException("malformed"));
        assertThatThrownBy(() -> interceptor.preSend(connect("Bearer error-token"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_rejectsUnauthenticatedStompAliasForConnect() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.STOMP);
        accessor.setLeaveMutable(true);
        Message<byte[]> stompMessage = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor().preSend(stompMessage, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_authenticatesValidStompAliasForConnect() {
        when(jwtUtils.validateJwtToken("token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("token")).thenReturn("client@example.com");
        when(userDetailsService.loadUserByUsername("client@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(true);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.STOMP);
        accessor.addNativeHeader("Authorization", "Bearer token");
        accessor.setLeaveMutable(true);
        Message<byte[]> stompMessage = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor().preSend(stompMessage, null);

        assertThat(StompHeaderAccessor.wrap(result).getUser()).isNotNull();
    }

    @Test
    void preSend_rejectsFramesWithoutAConnectedPrincipal() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_rejectsBrokerAndInternalSubscriptions() {
        for (String destination : List.of("/topic/simp-user-registry", "/topic/unresolved-user-destination",
                "/queue/chat-events-user123", "/user/other@example.com/queue/messages")) {
            assertThatThrownBy(() -> interceptor().preSend(frame(StompCommand.SUBSCRIBE, destination, "client@example.com"), null))
                    .as(destination).isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> interceptor().preSend(
                frame(StompCommand.SUBSCRIBE, "/user/queue/../../topic/simp-user-registry", "client@example.com"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void preSend_allowsOnlyUserScopedSubscriptionsAndApplicationSends() {
        assertThat(interceptor().preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/messages", "client@example.com"), null))
                .isNotNull();
        assertThat(interceptor().preSend(frame(StompCommand.SUBSCRIBE, "/user/topic/presence", "client@example.com"), null))
                .isNotNull();

        when(sessionRegistry.tokenFor("session-1")).thenReturn("token");
        when(jwtUtils.validateJwtToken("token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("token")).thenReturn("client@example.com");
        when(userDetailsService.loadUserByUsername("client@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(true);
        assertThat(interceptor().preSend(frame(StompCommand.SEND, "/app/v1/conversations", "client@example.com"), null))
                .isNotNull();
    }

    @Test
    void preSend_rejectsAccountWithoutLiveMessagingCapability() {
        User client = User.builder().email("client@example.com").accountRole(AccountRole.CLIENT).build();
        client.setId("client-id");
        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(client));
        when(capabilityService.compute(eq(client), any(Instant.class)))
                .thenReturn(new ViewerContext(client.getId(), AccountRole.CLIENT, PremiumStatus.none(), Set.of(), false));
        when(sessionRegistry.tokenFor("session-1")).thenReturn("token");
        when(jwtUtils.validateJwtToken("token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("token")).thenReturn("client@example.com");
        when(userDetailsService.loadUserByUsername("client@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(true);
        when(userDetails.isAccountNonLocked()).thenReturn(true);

        assertThatThrownBy(() -> interceptor().preSend(
                frame(StompCommand.SEND, "/app/v1/conversations", "client@example.com"), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Account does not have messaging access.");
    }

    @Test
    void preSend_rejectsBrokerSendsAndClosesExpiredSession() {
        assertThatThrownBy(() -> interceptor().preSend(frame(StompCommand.SEND, "/topic/events", "client@example.com"), null))
                .isInstanceOf(AccessDeniedException.class);

        when(sessionRegistry.tokenFor("session-1")).thenReturn("expired-token");
        when(jwtUtils.validateJwtToken("expired-token")).thenReturn(false);
        assertThatThrownBy(() -> interceptor().preSend(frame(StompCommand.SEND, "/app/v1/conversations", "client@example.com"), null))
                .isInstanceOf(AccessDeniedException.class);
        verify(sessionRegistry).close("session-1");
    }

    private WebSocketAuthInterceptor interceptor() {
        return new WebSocketAuthInterceptor(jwtUtils, userDetailsService, OperationalMetrics.noop(), sessionRegistry,
                userRepository, capabilityService, Clock.systemUTC());
    }

    private Message<byte[]> frame(StompCommand command, String destination, String username) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId("session-1");
        accessor.setUser(new UsernamePasswordAuthenticationToken(username, null, List.of()));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
