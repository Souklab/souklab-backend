package com.project.souklab.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Formations and masterclasses configuration bound to {@code app.formation.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.formation")
@Data
public class FormationProperties {

    /**
     * Formation thumbnail image configuration bound to {@code app.formation.thumbnail.*}.
     */
    private ThumbnailConfig thumbnail = new ThumbnailConfig();

    /**
     * Formation course attachments configuration bound to {@code app.formation.file.*}.
     */
    private FileConfig file = new FileConfig();

    /**
     * Formation cancellation policy configuration bound to {@code app.formation.cancellation.*}.
     */
    private CancellationConfig cancellation = new CancellationConfig();

    /**
     * Formation discovery and enrollment pagination configuration bound to {@code app.formation.pagination.*}.
     */
    private PaginationConfig pagination = new PaginationConfig();

    /**
     * Default ISO currency code for formations (default: DZD).
     */
    private String defaultCurrency;

    /**
     * Configuration for formation thumbnail images.
     */
    @Data
    public static class ThumbnailConfig {
        private DataSize maxFileSize;
        private List<String> allowedMimeTypes;
    }

    /**
     * Configuration for formation downloadable course files and syllabus attachments.
     */
    @Data
    public static class FileConfig {
        private int maxCount;
        private DataSize maxFileSize;
        private List<String> allowedMimeTypes;
    }

    /**
     * Configuration for formation enrollment cancellation rules.
     */
    @Data
    public static class CancellationConfig {
        private int deadlineHours;
    }

    /**
     * Configuration for formation catalog and enrollment pagination.
     */
    @Data
    public static class PaginationConfig {
        private int defaultPageSize;
    }
}
