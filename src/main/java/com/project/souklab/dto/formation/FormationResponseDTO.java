package com.project.souklab.dto.formation;

import com.project.souklab.filestorage.FileServingRoutes;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationReview;
import com.project.souklab.model.FormationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete data transfer object representing full details of a formation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationResponseDTO {

    /**
     * Unique identifier of the formation.
     */
    private String id;

    /**
     * Authoring instructor summary details.
     */
    private FormationAuthorDTO author;

    /**
     * Masterclass title or headline.
     */
    private String title;

    /**
     * Detailed narrative, objectives, and curriculum description.
     */
    private String description;

    /**
     * Publicly viewable showcase thumbnail image URL.
     */
    private String thumbnailUrl;

    /**
     * Workshop address or venue location.
     */
    private String location;

    /**
     * Flag indicating whether the masterclass is conducted remotely online.
     */
    private boolean isOnline;

    /**
     * Scheduled start timestamp for the session.
     */
    private LocalDateTime scheduledAt;

    /**
     * Total planned duration of the training in hours.
     */
    private int durationHours;

    /**
     * Maximum capacity of enrolled participant seats.
     */
    private int maxParticipants;

    /**
     * Admission fee per enrolled participant.
     */
    private int price;

    /**
     * ISO standard currency code.
     */
    private String currency;

    /**
     * Lifecycle status of the formation.
     */
    private FormationStatus status;

    /**
     * Current count of confirmed participant enrollments.
     */
    private long activeEnrollmentsCount;

    /**
     * Associated course files and syllabus attachments.
     */
    @Builder.Default
    private List<FormationFileResponseDTO> files = new ArrayList<>();

    /**
     * Moderation history and review decisions.
     */
    @Builder.Default
    private List<FormationReviewResponseDTO> reviews = new ArrayList<>();

    /**
     * Timestamp when the formation was initially created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the formation was last updated.
     */
    private LocalDateTime updatedAt;

    /**
     * Factory method mapping a Formation entity and its related child entities to a full FormationResponseDTO
     * with a configurable file-serving route prefix.
     *
     * @param formation the formation entity
     * @param activeFiles list of non-deleted formation files
     * @param reviews list of formation reviews
     * @param activeEnrollmentsCount count of confirmed enrollments
     * @param fileServingPrefix configured file-serving route prefix
     * @return populated FormationResponseDTO
     */
    public static FormationResponseDTO from(
            Formation formation,
            List<FormationFile> activeFiles,
            List<FormationReview> reviews,
            long activeEnrollmentsCount,
            String fileServingPrefix
    ) {
        return from(formation, activeFiles, reviews, activeEnrollmentsCount, fileServingPrefix, false);
    }

    public static FormationResponseDTO from(
            Formation formation,
            List<FormationFile> activeFiles,
            List<FormationReview> reviews,
            long activeEnrollmentsCount,
            String fileServingPrefix,
            boolean authorIsFormateur
    ) {
        if (formation == null) {
            return null;
        }
        List<FormationFileResponseDTO> fileDTOs = activeFiles != null
                ? activeFiles.stream().map(file -> FormationFileResponseDTO.from(file, fileServingPrefix)).toList()
                : List.of();
        List<FormationReviewResponseDTO> reviewDTOs = reviews != null
                ? reviews.stream().map(FormationReviewResponseDTO::from).toList()
                : List.of();

        return FormationResponseDTO.builder()
                .id(formation.getId())
                .author(FormationAuthorDTO.from(formation.getAuthor(), authorIsFormateur))
                .title(formation.getTitle())
                .description(formation.getDescription())
                .thumbnailUrl(formation.getThumbnailUrl())
                .location(formation.getLocation())
                .isOnline(formation.isOnline())
                .scheduledAt(formation.getScheduledAt())
                .durationHours(formation.getDurationHours())
                .maxParticipants(formation.getMaxParticipants())
                .price(formation.getPrice())
                .currency(formation.getCurrency())
                .status(formation.getStatus())
                .activeEnrollmentsCount(activeEnrollmentsCount)
                .files(fileDTOs)
                .reviews(reviewDTOs)
                .createdAt(formation.getCreatedAt())
                .updatedAt(formation.getUpdatedAt())
                .build();
    }

    /**
     * Factory method mapping a Formation entity and its related child entities to a full FormationResponseDTO
     * using default route prefix.
     *
     * @param formation the formation entity
     * @param activeFiles list of non-deleted formation files
     * @param reviews list of formation reviews
     * @param activeEnrollmentsCount count of confirmed enrollments
     * @return populated FormationResponseDTO
     */
    public static FormationResponseDTO from(
            Formation formation,
            List<FormationFile> activeFiles,
            List<FormationReview> reviews,
            long activeEnrollmentsCount
    ) {
        return from(formation, activeFiles, reviews, activeEnrollmentsCount, FileServingRoutes.DEFAULT_PREFIX);
    }
}
