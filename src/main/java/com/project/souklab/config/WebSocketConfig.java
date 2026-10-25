package com.project.souklab.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final ChatProperties chatProperties;
    private final CorsProperties corsProperties;
    private final RelayProperties relayProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(chatProperties.getWebsocketEndpoint())
                .setAllowedOriginPatterns(corsProperties.getAllowedOrigins().toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableStompBrokerRelay(chatProperties.getBrokerDestinationPrefixes().split(","))
                .setRelayHost(relayProperties.getHost())
                .setRelayPort(relayProperties.getPort())
                .setClientLogin(relayProperties.getClientLogin())
                .setClientPasscode(relayProperties.getClientPasscode())
                .setSystemLogin(relayProperties.getSystemLogin())
                .setSystemPasscode(relayProperties.getSystemPasscode())
                .setUserDestinationBroadcast(chatProperties.getUnresolvedUserDestination())
                .setUserRegistryBroadcast(chatProperties.getUserRegistryBroadcast());

        registry.setApplicationDestinationPrefixes(chatProperties.getApplicationDestinationPrefix());
        registry.setUserDestinationPrefix(chatProperties.getUserDestinationPrefix());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
