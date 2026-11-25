package com.project.souklab.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
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
    private final WebSocketSessionRegistry sessionRegistry;

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

    @Override
    public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(new WebSocketHandlerDecoratorFactory() {
            @Override
            public WebSocketHandler decorate(WebSocketHandler handler) {
                return new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session)
                            throws Exception {
                        sessionRegistry.register(session);
                        try {
                            super.afterConnectionEstablished(session);
                        } catch (Exception e) {
                            sessionRegistry.unregister(session.getId());
                            throw e;
                        }
                    }

                    @Override
                    public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session,
                                                       org.springframework.web.socket.CloseStatus closeStatus)
                            throws Exception {
                        sessionRegistry.unregister(session.getId());
                        super.afterConnectionClosed(session, closeStatus);
                    }
                };
            }
        });
    }
}
