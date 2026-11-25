package com.project.souklab.config;

import com.project.souklab.analytics.AnalyticsMetric;
import lombok.extern.slf4j.Slf4j;
import com.project.souklab.security.JwtUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Component
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;
    private final OperationalMetrics metrics;
    private final WebSocketSessionRegistry sessionRegistry;

    public WebSocketAuthInterceptor(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this(jwtUtils, userDetailsService, OperationalMetrics.noop(), WebSocketSessionRegistry.noop());
    }

    public WebSocketAuthInterceptor(JwtUtils jwtUtils, UserDetailsService userDetailsService,
                                    OperationalMetrics metrics) {
        this(jwtUtils, userDetailsService, metrics, WebSocketSessionRegistry.noop());
    }

    @Autowired
    public WebSocketAuthInterceptor(JwtUtils jwtUtils, UserDetailsService userDetailsService,
                                    OperationalMetrics metrics, WebSocketSessionRegistry sessionRegistry) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.metrics = metrics;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && SimpMessageType.CONNECT.equals(accessor.getMessageType())) {
            List<String> authorization = accessor.getNativeHeader("Authorization");

            if (authorization != null && !authorization.isEmpty()) {
                String token = authorization.get(0);
                if (token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }

                try {
                    if (jwtUtils.validateJwtToken(token)) {
                        String username = jwtUtils.getUserNameFromJwtToken(token);
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                            throw new AccessDeniedException("Account is not permitted to establish a WebSocket connection.");
                        }
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        accessor.setUser(authentication);
                        sessionRegistry.authenticate(accessor.getSessionId(), token, username);
                        metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.AUTHENTICATED);
                        return message;
                    }
                } catch (Exception e) {
                    log.warn("WebSocket authentication failed");
                }
            }
                        metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.REJECTED);
            throw new AccessDeniedException("A valid Bearer token is required to establish a WebSocket connection.");
        }

        if (accessor != null && (accessor.getCommand() == StompCommand.SUBSCRIBE
                || accessor.getCommand() == StompCommand.SEND)) {
            if (!(accessor.getUser() instanceof Authentication authentication)
                    || !authentication.isAuthenticated()) {
                metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.REJECTED);
                throw new AccessDeniedException("An authenticated principal is required for STOMP frames.");
            }
            StompCommand command = accessor.getCommand();
            String destination = accessor.getDestination();
            if (command == StompCommand.SUBSCRIBE) {
                if (!isAllowedSubscription(destination)) {
                    metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.REJECTED);
                    throw new AccessDeniedException("STOMP subscription destination is not permitted.");
                }
            } else if (command == StompCommand.SEND) {
                if (destination == null || !destination.startsWith("/app/")) {
                    metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.REJECTED);
                    throw new AccessDeniedException("STOMP send destination is not permitted.");
                }
                String sessionId = accessor.getSessionId();
                String username = authentication.getName();
                String token = sessionRegistry.tokenFor(sessionId);
                if (token == null || !jwtUtils.validateJwtToken(token)
                        || !username.equals(jwtUtils.getUserNameFromJwtToken(token))) {
                    sessionRegistry.close(sessionId);
                    throw new AccessDeniedException("WebSocket authentication has expired.");
                }
                UserDetails current = userDetailsService.loadUserByUsername(username);
                if (!current.isEnabled() || !current.isAccountNonLocked()) {
                    sessionRegistry.close(sessionId);
                    throw new AccessDeniedException("Account is not permitted to send WebSocket messages.");
                }
            }
        }
        return message;
    }

    private boolean isAllowedSubscription(String destination) {
        if ("/user/topic/presence".equals(destination)) return true;
        if (destination == null || !destination.startsWith("/user/queue/")) return false;
        String queuePath = destination.substring("/user/queue/".length());
        if (queuePath.isBlank() || queuePath.contains("\\") || queuePath.contains("%") || queuePath.contains("//")) {
            return false;
        }
        for (String segment : queuePath.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }
}
