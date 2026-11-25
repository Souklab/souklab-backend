package com.project.souklab.config;

import com.project.souklab.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketSessionRegistryTest {
    @Mock JwtUtils jwtUtils;
    @Mock UserDetailsService userDetailsService;
    @Mock UserDetails userDetails;
    @Mock WebSocketSession session;

    @Test
    void closeRevokedSessions_closesExpiredTokens() throws Exception {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(jwtUtils.validateJwtToken("expired-token")).thenReturn(false);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(jwtUtils, userDetailsService);
        registry.register(session);
        registry.authenticate("session-1", "expired-token", "client@example.com");

        registry.closeRevokedSessions();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void closeRevokedSessions_closesNewlyDisabledAccounts() throws Exception {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(jwtUtils.validateJwtToken("valid-token")).thenReturn(true);
        when(jwtUtils.getUserNameFromJwtToken("valid-token")).thenReturn("client@example.com");
        when(userDetailsService.loadUserByUsername("client@example.com")).thenReturn(userDetails);
        when(userDetails.isEnabled()).thenReturn(false);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(jwtUtils, userDetailsService);
        registry.register(session);
        registry.authenticate("session-1", "valid-token", "client@example.com");

        registry.closeRevokedSessions();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
