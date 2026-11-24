package com.project.souklab.config;

import com.project.souklab.analytics.AnalyticsMetric;
import lombok.extern.slf4j.Slf4j;
import com.project.souklab.security.JwtUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
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

    public WebSocketAuthInterceptor(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this(jwtUtils, userDetailsService, OperationalMetrics.noop());
    }

    @Autowired
    public WebSocketAuthInterceptor(JwtUtils jwtUtils, UserDetailsService userDetailsService,
                                    OperationalMetrics metrics) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.metrics = metrics;
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

        if (accessor != null && accessor.getUser() == null) {
            metrics.recordWebSocket(AnalyticsMetric.Operational.Outcome.REJECTED);
            throw new AccessDeniedException("An authenticated principal is required for STOMP frames.");
        }
        return message;
    }
}
