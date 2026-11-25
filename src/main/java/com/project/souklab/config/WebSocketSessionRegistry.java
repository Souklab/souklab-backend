package com.project.souklab.config;

import com.project.souklab.security.JwtUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks authenticated WebSocket sessions so revoked access is closed within five seconds. */
@Component
@Slf4j
public class WebSocketSessionRegistry {

    static final long REVOCATION_CHECK_INTERVAL_MS = 5_000;
    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, Credentials> credentialsAwaitingTransport = new ConcurrentHashMap<>();
    private final boolean enabled;

    @Autowired
    public WebSocketSessionRegistry(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this(jwtUtils, userDetailsService, true);
    }

    private WebSocketSessionRegistry(JwtUtils jwtUtils, UserDetailsService userDetailsService, boolean enabled) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.enabled = enabled;
    }

    static WebSocketSessionRegistry noop() {
        return new WebSocketSessionRegistry(null, null, false);
    }

    public synchronized void register(WebSocketSession session) {
        if (!enabled) return;
        SessionEntry entry = new SessionEntry(session);
        Credentials credentials = credentialsAwaitingTransport.remove(session.getId());
        if (credentials != null) entry.setCredentials(credentials);
        sessions.put(session.getId(), entry);
    }

    public synchronized void unregister(String sessionId) {
        sessions.remove(sessionId);
        credentialsAwaitingTransport.remove(sessionId);
    }

    public synchronized void authenticate(String sessionId, String token, String username) {
        if (!enabled || sessionId == null) return;
        Credentials credentials = new Credentials(token, username);
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) credentialsAwaitingTransport.put(sessionId, credentials);
        else entry.setCredentials(credentials);
    }

    public String tokenFor(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        return entry == null ? null : entry.token;
    }

    public void close(String sessionId) {
        SessionEntry entry = sessions.remove(sessionId);
        credentialsAwaitingTransport.remove(sessionId);
        close(entry);
    }

    @Scheduled(fixedDelay = REVOCATION_CHECK_INTERVAL_MS)
    public void closeRevokedSessions() {
        if (!enabled) return;
        sessions.forEach((sessionId, entry) -> {
            if (!entry.session.isOpen()) {
                unregister(sessionId);
                return;
            }
            if (entry.token == null) return;
            try {
                if (!jwtUtils.validateJwtToken(entry.token)
                        || !entry.username.equals(jwtUtils.getUserNameFromJwtToken(entry.token))) {
                    close(sessionId);
                    return;
                }
                UserDetails current = userDetailsService.loadUserByUsername(entry.username);
                if (!current.isEnabled() || !current.isAccountNonLocked()) close(sessionId);
            } catch (RuntimeException e) {
                log.debug("Closing WebSocket session after authentication recheck failed");
                close(sessionId);
            }
        });
    }

    private void close(SessionEntry entry) {
        if (entry == null || !entry.session.isOpen()) return;
        try {
            entry.session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception e) {
            log.debug("Unable to close revoked WebSocket session");
        }
    }

    private record Credentials(String token, String username) { }

    private static final class SessionEntry {
        private final WebSocketSession session;
        private volatile String token;
        private volatile String username;

        private SessionEntry(WebSocketSession session) { this.session = session; }
        private void setCredentials(Credentials credentials) {
            this.username = credentials.username();
            this.token = credentials.token();
        }
    }
}
