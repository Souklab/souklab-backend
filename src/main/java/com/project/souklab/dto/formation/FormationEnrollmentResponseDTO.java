package com.project.souklab.dto.formation;

import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.Artisan;
import java.util.Optional;
import com.project.souklab.model.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data transfer object representing a peer artisan's masterclass enrollment reservation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationEnrollmentResponseDTO {

    /**
     * Unique identifier of the enrollment record.
     */
    private String id;

    /**
     * Unique identifier of the masterclass formation.
     */
    private String formationId;

    /**
     * Title of the enrolled masterclass formation.
     */
    private String formationTitle;

    /**
     * Unique identifier of the enrolled artisan.
     */
    private String artisanId;

    /**
     * Full display name of the enrolled peer artisan.
     */
    private String artisanName;

    /**
     * Current status of this enrollment seat.
     */
    private EnrollmentStatus status;

    /**
     * Timestamp when the enrollment seat was confirmed.
     */
    private LocalDateTime enrolledAt;

    /**
     * Timestamp when the enrollment was cancelled, if applicable.
     */
    private LocalDateTime cancelledAt;

    /**
     * Factory method mapping a FormationEnrollment entity to its response DTO.
     *
     * @param enrollment the enrollment entity
     * @return populated FormationEnrollmentResponseDTO
     */
    public static FormationEnrollmentResponseDTO from(FormationEnrollment enrollment) {
        if (enrollment == null) {
            return null;
        }

        String formId = enrollment.getFormation() != null ? enrollment.getFormation().getId() : null;
        String formTitle = enrollment.getFormation() != null ? enrollment.getFormation().getTitle() : null;
        String artId = Optional.ofNullable(enrollment.getArtisan()).map(Artisan::getId).orElse(null);
        String artName = null;

        User artisanUser = Optional.ofNullable(enrollment.getArtisan()).map(artisan -> artisan.getUser()).orElse(null);
        if (artisanUser != null) {
            User user = artisanUser;
            String first = user.getFirstName() != null ? user.getFirstName() : "";
            String last = user.getLastName() != null ? user.getLastName() : "";
            artName = (first + " " + last).trim();
            if (artName.isEmpty()) {
                artName = user.getEmail();
            }
        }

        return FormationEnrollmentResponseDTO.builder()
                .id(enrollment.getId())
                .formationId(formId)
                .formationTitle(formTitle)
                .artisanId(artId)
                .artisanName(artName)
                .status(enrollment.getStatus())
                .enrolledAt(enrollment.getEnrolledAt())
                .cancelledAt(enrollment.getCancelledAt())
                .build();
    }
}
