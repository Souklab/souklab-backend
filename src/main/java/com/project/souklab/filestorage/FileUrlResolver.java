package com.project.souklab.filestorage;

import com.project.souklab.config.AppStorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Central resolver for converting internal opaque storage keys into public or CDN accessible URLs.
 * Encapsulates file-serving prefix formatting and trailing slash normalization.
 */
@Component
@RequiredArgsConstructor
public class FileUrlResolver {

    private final AppStorageProperties appStorageProperties;

    /**
     * Resolves an internal storage key to its public URL representation.
     *
     * @param storageKey the raw storage key (e.g. UUID filename)
     * @return the fully qualified or relative public file serving URL, or null if key is null or blank
     */
    public String toUrl(String storageKey) {
        return appStorageProperties.toUrl(storageKey);
    }

    /**
     * Returns the configured file serving prefix normalized with a trailing slash.
     *
     * @return normalized file serving prefix ending with '/'
     */
    public String getPrefix() {
        return appStorageProperties.resolveFileServingPrefix();
    }

    /**
     * Extracts an opaque storage key from a URL created by this resolver.
     *
     * @param fileUrl relative file-serving URL
     * @return storage key, or {@code null} when the URL is not managed by this resolver
     */
    public String toStorageKey(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            return null;
        }
        String prefix = getPrefix();
        if (!fileUrl.startsWith(prefix)) {
            return null;
        }
        String storageKey = fileUrl.substring(prefix.length());
        return storageKey.isBlank() || storageKey.contains("/") ? null : storageKey;
    }
}
