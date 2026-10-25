package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Artisan showcase and certification configuration bound to {@code app.artisan.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.artisan")
@Data
public class ArtisanProperties {

    private GalleryConfig gallery = new GalleryConfig();
    private CertificationConfig certification = new CertificationConfig();
    private FormateurConfig formateur = new FormateurConfig();

    /**
     * Formateur status and reapplication configuration bound to {@code app.artisan.formateur.*}.
     */
    @Data
    public static class FormateurConfig {
        private long reapplyCooldownDays;
    }

    /**
     * Gallery showcase portfolio configuration bound to {@code app.artisan.gallery.*}.
     */
    @Data
    public static class GalleryConfig {
        private int maxImages;
        private DataSize maxFileSize;
        private List<String> allowedMimeTypes;
    }

    /**
     * Official certification and accreditation configuration bound to {@code app.artisan.certification.*}.
     */
    @Data
    public static class CertificationConfig {
        private int maxCount;
        private DataSize maxFileSize;
        private List<String> allowedMimeTypes;
    }
}
