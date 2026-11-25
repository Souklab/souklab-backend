package com.project.souklab.dto.formation;

import com.project.souklab.model.FormationEnrollment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data transfer object encapsulating detailed participant enrollment info alongside parent formation summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormationEnrollmentDetailDTO {

    /**
     * Enrollment reservation metadata and status.
     */
    private FormationEnrollmentResponseDTO enrollment;

    /**
     * Masterclass formation summary card details.
     */
    private FormationSummaryDTO formation;

    /**
     * Factory method mapping a FormationEnrollment entity to FormationEnrollmentDetailDTO.
     *
     * @param enrollment the enrollment entity
     * @param activeEnrollmentsCount count of active confirmed enrollments in the formation
     * @return populated FormationEnrollmentDetailDTO
     */
    public static FormationEnrollmentDetailDTO from(FormationEnrollment enrollment, long activeEnrollmentsCount) {
        return from(enrollment, activeEnrollmentsCount, false);
    }

    public static FormationEnrollmentDetailDTO from(FormationEnrollment enrollment, long activeEnrollmentsCount,
                                                    boolean authorIsFormateur) {
        if (enrollment == null) {
            return null;
        }
        return FormationEnrollmentDetailDTO.builder()
                .enrollment(FormationEnrollmentResponseDTO.from(enrollment))
                .formation(FormationSummaryDTO.from(enrollment.getFormation(), activeEnrollmentsCount, authorIsFormateur))
                .build();
    }
}
