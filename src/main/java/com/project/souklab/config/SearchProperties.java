package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Search and indexing configuration bound to {@code app.search.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.search")
@Data
public class SearchProperties {

    private boolean enabled;
    private String uris;
    private String username;
    private String password;
    private int connectionTimeout;
    private int readTimeout;
    private String indexPrefix;
    private String schemaManagement;
    private boolean schemaBootstrapEnabled;
    private boolean syncOnStartup;
    private MassIndexing massIndexing = new MassIndexing();

    @Data
    public static class MassIndexing {
        private int threadsToLoadObjects;
        private int batchSizeToLoadObjects;
        private int idFetchSize;
    }
}
