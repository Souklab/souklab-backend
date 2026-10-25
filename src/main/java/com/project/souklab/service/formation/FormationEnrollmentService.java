package com.project.souklab.service.formation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.config.FormationProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.specification.FormationSpecifications;
import com.project.souklab.model.User;
import org.springframework.data.jpa.domain.Specification;
import com.project.souklab.dto.formation.FormationEnrollmentDetailDTO;
import com.project.souklab.dto.formation.FormationEnrollmentResponseDTO;
import com.project.souklab.dto.formation.FormationPublicViewDTO;
import com.project.souklab.dto.formation.FormationEnrollmentSummaryDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResource;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.util.ArtisanSecurityUtils;
import com.project.souklab.security.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service managing peer artisan workshop discovery, enrollment capacity enforcement,
 * cancellation policy deadlines, and protected course file access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormationEnrollmentService {

    private static final int DEFAULT_PAGE_NUMBER = 0;
    private static final String SORT_PROPERTY_SCHEDULED_AT = "scheduledAt";
    private static final String SORT_PROPERTY_ENROLLED_AT = "enrolledAt";
    private static final String ERROR_FORMATION_NOT_FOUND_PREFIX = "Formation not found with ID: ";

    private final FormationRepository formationRepository;
    private final FormationEnrollmentRepository formationEnrollmentRepository;
    private final ArtisanRepository artisanRepository;
    private final FormationFileRepository formationFileRepository;
    private final StorageService storageService;
    private final FormationProperties formationProperties;
    private final Clock clock;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }


    /**
     * Retrieves paginated published masterclasses for peer artisan catalog discovery with optional trade,
     * region, and delivery mode filters.
     *
     * @param trade trade or craft category filter
     * @param region region or wilaya code/slug filter
     * @param online delivery mode filter
     * @param pageable pagination and sorting parameters
     * @return page of formation summary cards
     */
    @Transactional(readOnly = true)
    public Page<FormationSummaryDTO> getPublishedCatalog(String trade, String region, Boolean online, Pageable pageable) {
        Pageable effectivePageable = (pageable != null && pageable.getSort().isSorted())
                ? pageable
                : (pageable != null)
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, SORT_PROPERTY_SCHEDULED_AT))
                : PageRequest.of(DEFAULT_PAGE_NUMBER, formationProperties.getPagination().getDefaultPageSize(), Sort.by(Sort.Direction.ASC, SORT_PROPERTY_SCHEDULED_AT));

        Page<Formation> formations;
        if (trade == null && region == null && online == null) {
            formations = formationRepository.findByStatusAndDeletedAtIsNull(FormationStatus.PUBLISHED, effectivePageable);
        } else {
            Specification<Formation> spec = FormationSpecifications.filterCatalog(trade, region, online);
            formations = formationRepository.findAll(spec, effectivePageable);
        }

        return formations.map(formation -> {
            long activeEnrollments = formationEnrollmentRepository.countByFormationIdAndStatus(formation.getId(), EnrollmentStatus.CONFIRMED);
            return FormationSummaryDTO.from(formation, activeEnrollments);
        });
    }

    /**
     * Retrieves paginated published masterclasses for peer artisan catalog discovery without filters.
     *
     * @param pageable pagination and sorting parameters
     * @return page of formation summary cards
     */
    @Transactional(readOnly = true)
    public Page<FormationSummaryDTO> getPublishedCatalog(Pageable pageable) {
        return getPublishedCatalog(null, null, null, pageable);
    }

    /**
     * Retrieves detailed public information for a published formation, including capacity,
     * caller enrollment status, and course syllabus file descriptors.
     *
     * @param formationId unique identifier of the formation
     * @return comprehensive public view DTO
     */
    @Transactional
    public FormationPublicViewDTO getPublishedFormationDetails(String formationId) {
        Formation formation = formationRepository.findWithLockByIdAndDeletedAtIsNull(formationId)
                .or(() -> formationRepository.findByIdAndDeletedAtIsNull(formationId))
                .filter(f -> f.getStatus() == FormationStatus.PUBLISHED || f.getStatus() == FormationStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException("Published formation not found with ID: " + formationId));

        Artisan artisan = resolveAuthenticatedArtisan();

        boolean isAuthor = formation.getAuthor() != null && formation.getAuthor().getId().equals(artisan.getId());
        boolean isEnrolled = formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                formation.getId(),
                artisan.getId(),
                EnrollmentStatus.CONFIRMED
        ) || formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                formation.getId(),
                artisan.getId(),
                EnrollmentStatus.ATTENDED
        );

        long activeEnrollmentsCount = formationEnrollmentRepository.countByFormationIdAndStatus(
                formation.getId(),
                EnrollmentStatus.CONFIRMED
        );

        List<FormationFile> activeFiles = formationFileRepository.findByFormationIdAndDeletedAtIsNull(formation.getId());

        return FormationPublicViewDTO.from(formation, activeFiles, activeEnrollmentsCount, isEnrolled, isAuthor);
    }

    /**
     * Enrolls the authenticated artisan into a published masterclass formation.
     * Validates that caller is not the author, seats remain within max capacity,
     * and reactivates previously cancelled registrations when applicable.
     *
     * @param formationId unique identifier of the formation
     * @return confirmed enrollment response DTO
     */
    @Transactional
    public FormationEnrollmentResponseDTO enrollInFormation(String formationId) {
        Artisan artisan = resolveAuthenticatedArtisan();

        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(formationId)
                .filter(f -> f.getStatus() == FormationStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Published formation not found with ID: " + formationId));

        if (formation.getAuthor() != null && formation.getAuthor().getId().equals(artisan.getId())) {
            throw new ConflictException("Authors cannot enroll in their own formations.");
        }

        long activeEnrollmentsCount = formationEnrollmentRepository.countByFormationIdAndStatus(formationId, EnrollmentStatus.CONFIRMED);
        if (activeEnrollmentsCount >= formation.getMaxParticipants()) {
            throw new ConflictException("Formation is fully booked. Maximum capacity reached.");
        }

        Optional<FormationEnrollment> existingOpt = formationEnrollmentRepository.findByFormationIdAndArtisanId(formationId, artisan.getId());
        FormationEnrollment enrollment;

        if (existingOpt.isPresent()) {
            FormationEnrollment existing = existingOpt.get();
            if (existing.getStatus() == EnrollmentStatus.CONFIRMED) {
                throw new ConflictException("You are already enrolled in this formation.");
            }
            if (existing.getStatus() == EnrollmentStatus.CANCELLED) {
                existing.setStatus(EnrollmentStatus.CONFIRMED);
                existing.setCancelledAt(null);
                existing.setEnrolledAt(LocalDateTime.now(clock));
                enrollment = formationEnrollmentRepository.save(existing);
            } else {
                throw new ConflictException("Cannot enroll in this formation with status: " + existing.getStatus());
            }
        } else {
            FormationEnrollment newEnrollment = FormationEnrollment.builder()
                    .formation(formation)
                    .artisan(artisan)
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now(clock))
                    .build();
            enrollment = formationEnrollmentRepository.save(newEnrollment);
        }

        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Formation.ENROLLMENT, artisan.getId(), formation.getId(),
                    Map.of(AnalyticsMetadata.State.STATUS, enrollment.getStatus()));
        }
        return FormationEnrollmentResponseDTO.from(enrollment);
    }

    /**
     * Cancels an active enrollment reservation for the authenticated artisan.
     * Enforces the configured cancellation cutoff deadline prior to scheduled masterclass start.
     *
     * @param formationId unique identifier of the formation
     * @return updated enrollment response DTO with CANCELLED status
     */
    @Transactional
    public FormationEnrollmentResponseDTO cancelEnrollment(String formationId) {
        Artisan artisan = resolveAuthenticatedArtisan();

        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(formationId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_FORMATION_NOT_FOUND_PREFIX + formationId));

        FormationEnrollment enrollment = formationEnrollmentRepository
                .findByFormationIdAndArtisanIdAndStatus(formationId, artisan.getId(), EnrollmentStatus.CONFIRMED)
                .orElseThrow(() -> new ResourceNotFoundException("Active confirmed enrollment not found for formation ID: " + formationId));

        int deadlineHours = formationProperties.getCancellation().getDeadlineHours();
        if (formation.getScheduledAt() != null) {
            LocalDateTime cutoff = formation.getScheduledAt().minusHours(deadlineHours);
            if (LocalDateTime.now(clock).isAfter(cutoff)) {
                throw new BadRequestException("Cancellations must be made at least " + deadlineHours + " hours before the scheduled start time.");
            }
        }

        enrollment.setStatus(EnrollmentStatus.CANCELLED);
        enrollment.setCancelledAt(LocalDateTime.now(clock));
        FormationEnrollment saved = formationEnrollmentRepository.save(enrollment);

        return FormationEnrollmentResponseDTO.from(saved);
    }

    /** Records attendance for one participant; only the formation author may do so.
     * Allowed transitions: CONFIRMED → ATTENDED, CONFIRMED → ABSENT, ATTENDED → ABSENT, ABSENT → ATTENDED. */
    @Transactional
    public FormationEnrollmentResponseDTO markAttendance(String formationId, String enrollmentId, EnrollmentStatus attendanceStatus) {
        Artisan author = resolveAuthenticatedArtisan();
        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(formationId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_FORMATION_NOT_FOUND_PREFIX + formationId));
        if (formation.getAuthor() == null || !formation.getAuthor().getId().equals(author.getId())) {
            throw new ForbiddenException("Only the formation author may record attendance.");
        }
        FormationEnrollment enrollment = formationEnrollmentRepository.findById(enrollmentId)
                .filter(candidate -> candidate.getFormation() != null
                        && formationId.equals(candidate.getFormation().getId())
                        && candidate.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found for formation."));
        if (attendanceStatus != EnrollmentStatus.ATTENDED && attendanceStatus != EnrollmentStatus.ABSENT) {
            throw new ConflictException("Attendance status must be ATTENDED or ABSENT.");
        }
        if (enrollment.getStatus() != EnrollmentStatus.CONFIRMED
                && enrollment.getStatus() != EnrollmentStatus.ATTENDED
                && enrollment.getStatus() != EnrollmentStatus.ABSENT) {
            throw new ConflictException("Only confirmed or already-marked enrollments can have attendance updated.");
        }
        enrollment.setStatus(attendanceStatus);
        return FormationEnrollmentResponseDTO.from(formationEnrollmentRepository.save(enrollment));
    }

    /**
     * Returns a paginated participant roster for the authenticated formation author.
     * Participant names use {@link User#getPublicDisplayName()} to avoid PII exposure.
     *
     * @param formationId unique identifier of the formation
     * @param pageable pagination parameters
     * @return paginated list of enrollment summaries
     */
    @Transactional(readOnly = true)
    public Page<FormationEnrollmentSummaryDTO> getEnrollmentsForAuthor(String formationId, Pageable pageable) {
        Artisan author = resolveAuthenticatedArtisan();
        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(formationId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_FORMATION_NOT_FOUND_PREFIX + formationId));
        if (formation.getAuthor() == null || !formation.getAuthor().getId().equals(author.getId())) {
            throw new ForbiddenException("Only the formation author can view the participant roster.");
        }
        Pageable effectivePageable = (pageable != null && pageable.getSort().isSorted())
                ? pageable
                : (pageable != null)
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, SORT_PROPERTY_ENROLLED_AT))
                : PageRequest.of(DEFAULT_PAGE_NUMBER, formationProperties.getPagination().getDefaultPageSize(), Sort.by(Sort.Direction.ASC, SORT_PROPERTY_ENROLLED_AT));
        return formationEnrollmentRepository.findByFormationIdAndDeletedAtIsNull(formationId, effectivePageable)
                .map(enrollment -> new FormationEnrollmentSummaryDTO(
                        enrollment.getId(),
                        enrollment.getArtisan().getUser().getPublicDisplayName(),
                        enrollment.getStatus(),
                        enrollment.getEnrolledAt()
                ));
    }


    /**
     * Retrieves paginated active and past enrollment history for the authenticated artisan.
     *
     * @param pageable pagination parameters
     * @return page of detailed enrollment records
     */
    @Transactional(readOnly = true)
    public Page<FormationEnrollmentDetailDTO> getMyEnrollments(Pageable pageable) {
        Artisan artisan = resolveAuthenticatedArtisan();

        Pageable effectivePageable = (pageable != null && pageable.getSort().isSorted())
                ? pageable
                : (pageable != null)
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, SORT_PROPERTY_ENROLLED_AT))
                : PageRequest.of(DEFAULT_PAGE_NUMBER, formationProperties.getPagination().getDefaultPageSize(), Sort.by(Sort.Direction.DESC, SORT_PROPERTY_ENROLLED_AT));

        Page<FormationEnrollment> enrollments = formationEnrollmentRepository
                .findByArtisanIdAndDeletedAtIsNull(artisan.getId(), effectivePageable);

        return enrollments.map(enrollment -> {
            long activeCount = formationEnrollmentRepository.countByFormationIdAndStatus(
                    enrollment.getFormation().getId(),
                    EnrollmentStatus.CONFIRMED
            );
            return FormationEnrollmentDetailDTO.from(enrollment, activeCount);
        });
    }

    /**
     * Retrieves a protected course file attachment for download.
     * Access is restricted to confirmed enrolled participants and the authoring instructor.
     *
     * @param formationId unique identifier of the parent formation
     * @param fileId unique identifier of the course file
     * @return storage resource containing file data stream and metadata
     */
    @Transactional(readOnly = true)
    public StorageResource downloadCourseFile(String formationId, String fileId) {
        Artisan artisan = resolveAuthenticatedArtisan();

        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(formationId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_FORMATION_NOT_FOUND_PREFIX + formationId));

        FormationFile file = formationFileRepository.findByIdAndFormationIdAndDeletedAtIsNull(fileId, formationId)
                .orElseThrow(() -> new ResourceNotFoundException("Course file not found with ID: " + fileId));

        boolean isAuthor = formation.getAuthor() != null && formation.getAuthor().getId().equals(artisan.getId());
        boolean isEnrolled = formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                formationId,
                artisan.getId(),
                EnrollmentStatus.CONFIRMED
        ) || formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                formationId,
                artisan.getId(),
                EnrollmentStatus.ATTENDED
        );

        if (!isAuthor && !isEnrolled) {
            throw new ForbiddenException("Access denied: Course syllabus files are available only to confirmed enrolled participants.");
        }

        return storageService.load(file.getStorageKey());
    }

    /**
     * Checks if the authenticated artisan is the author of the specified formation.
     *
     * @param formationId formation unique identifier
     * @return true if the current authenticated artisan authored the formation, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean isAuthor(String formationId) {
        Artisan artisan = resolveAuthenticatedArtisan();
        return formationRepository.findByIdAndAuthorIdAndDeletedAtIsNull(formationId, artisan.getId()).isPresent();
    }

    /**
     * Delegates artisan identity resolution to the shared {@link ArtisanSecurityUtils} component.
     *
     * @return resolved Artisan entity for the current authenticated principal
     */
    private Artisan resolveAuthenticatedArtisan() {
        return ArtisanSecurityUtils.resolveAuthenticatedArtisan(artisanRepository, Permission.Artisan.FORMATIONS);
    }
}
