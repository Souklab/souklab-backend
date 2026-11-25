package com.project.souklab.dto.formation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Comprehensive public catalog representation for masterclass formation details.
 * Contains calculated capacity availability, caller enrollment status, and course syllabus file descriptors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationPublicViewDTO {

    public static final String FORMATION_FILE_DOWNLOAD_PATH_PREFIX = "/api/v1/artisan/formations/";

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
     * Detailed narrative, learning objectives, and curriculum description.
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
    @JsonProperty("isOnline")
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
     * Number of remaining unoccupied participant seats.
     */
    private long availableSeats;

    /**
     * Flag indicating whether the authenticated requesting artisan is currently enrolled.
     */
    @JsonProperty("isEnrolled")
    private boolean isEnrolled;

    /**
     * Attached syllabus course files with conditionally gated download URLs.
     */
    @Builder.Default
    private List<FormationFileDescriptorDTO> files = Collections.emptyList();

    /**
     * Timestamp when the formation was initially drafted.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the formation was last updated.
     */
    private LocalDateTime updatedAt;

    /**
     * Factory method mapping a Formation entity and its attachments into a FormationPublicViewDTO.
     *
     * @param formation the formation entity
     * @param activeFiles active course attachment files
     * @param activeEnrollmentsCount count of confirmed active enrollments
     * @param isEnrolled boolean indicating whether requesting caller is enrolled
     * @param isAuthor boolean indicating whether requesting caller is the author
     * @return populated FormationPublicViewDTO
     */
    public static FormationPublicViewDTO from(
            Formation formation,
            List<FormationFile> activeFiles,
            long activeEnrollmentsCount,
            boolean isEnrolled,
            boolean isAuthor
    ) {
        return from(formation, activeFiles, activeEnrollmentsCount, isEnrolled, isAuthor, false);
    }

    public static FormationPublicViewDTO from(
            Formation formation,
            List<FormationFile> activeFiles,
            long activeEnrollmentsCount,
            boolean isEnrolled,
            boolean isAuthor,
            boolean authorIsFormateur
    ) {
        if (formation == null) {
            return null;
        }

        long seatsAvailable = Math.max(0, formation.getMaxParticipants() - activeEnrollmentsCount);
        boolean canDownload = isEnrolled || isAuthor;

        List<FormationFileDescriptorDTO> fileDescriptors = (activeFiles == null)
                ? Collections.emptyList()
                : activeFiles.stream()
                .map(f -> {
                    String downloadUrl = canDownload
                            ? FORMATION_FILE_DOWNLOAD_PATH_PREFIX + formation.getId() + "/files/" + f.getId() + "/download"
                            : null;
                    return FormationFileDescriptorDTO.from(f, downloadUrl);
                })
                .toList();

        return FormationPublicViewDTO.builder()
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
                .availableSeats(seatsAvailable)
                .isEnrolled(isEnrolled)
                .files(fileDescriptors)
                .createdAt(formation.getCreatedAt())
                .updatedAt(formation.getUpdatedAt())
                .build();
    }
}
