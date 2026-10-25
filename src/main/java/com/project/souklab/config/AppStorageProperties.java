package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.storage")
@Data
public class AppStorageProperties {

    private String uploadDir;
    private String thumbnails;
    private String indexes;

    /**
     * Route prefix for public or authenticated file streaming endpoints (default: /api/v1/files/).
     */
    private String fileServingPrefix;

    /**
     * Returns the configured file-serving route prefix normalised with a guaranteed trailing slash.
     * Callers should use this instead of rolling their own prefix resolution.
     *
     * @return normalised route prefix ending with {@code /}
     */
    public String resolveFileServingPrefix() {
        if (fileServingPrefix == null || fileServingPrefix.isBlank()) {
            throw new IllegalStateException("app.storage.file-serving-prefix must be configured");
        }
        return fileServingPrefix.endsWith("/") ? fileServingPrefix : fileServingPrefix + "/";
    }

    /**
     * Resolves an internal storage key to its public or CDN accessible URL representation.
     *
     * @param storageKey the raw storage key (e.g. UUID filename)
     * @return the fully qualified or relative public file serving URL, or null if key is null or blank
     */
    public String toUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        return resolveFileServingPrefix() + storageKey;
    }
}
