package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.chat")
@Data
public class ChatProperties {

    private int messageMaxLength;
    private int attachmentMaxCount;
    private int minPageSize;
    private int defaultPageSize;
    private int maxPageSize;
    private Duration cursorLifetime;
    private Duration typingEventInterval;
    private String websocketProtocolVersion;
    private String applicationDestinationPrefix;
    private String userDestinationPrefix;
    private String messageDestinationPrefix;
    private String eventDestination;
    private String presenceDestination;
    private String notificationDestination;
    private String websocketEndpoint;
    private String brokerDestinationPrefixes;
    private String unresolvedUserDestination;
    private String userRegistryBroadcast;
}
