package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.favorites")
@Data
public class FavoritesProperties {

    private int maxPerClient;
}
