package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.directory")
@Data
public class DirectoryProperties {

    private int defaultPageIndex;
    private int defaultPageSize;
    private int minPageSize;
    private int maxPageSize;
}
