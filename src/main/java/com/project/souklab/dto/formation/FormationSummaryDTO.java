package com.project.souklab.dto.formation;

import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight summary card DTO representing a formation in lists and catalog results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationSummaryDTO {

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
     * Timestamp when the formation was initially drafted.
     */
    private LocalDateTime createdAt;

    /**
     * Factory method mapping a Formation entity to a FormationSummaryDTO.
     *
     * @param formation the formation entity
     * @param activeEnrollmentsCount count of active confirmed enrollments
     * @return populated FormationSummaryDTO
     */
    public static FormationSummaryDTO from(Formation formation, long activeEnrollmentsCount) {
        return from(formation, activeEnrollmentsCount, false);
    }

    public static FormationSummaryDTO from(Formation formation, long activeEnrollmentsCount,
                                           boolean authorIsFormateur) {
        if (formation == null) {
            return null;
        }
        return FormationSummaryDTO.builder()
                .id(formation.getId())
                .author(FormationAuthorDTO.from(formation.getAuthor(), authorIsFormateur))
                .title(formation.getTitle())
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
                .createdAt(formation.getCreatedAt())
                .build();
    }
}
