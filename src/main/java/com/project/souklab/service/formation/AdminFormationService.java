package com.project.souklab.service.formation;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.FormationReviewRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationReviewRequestDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.FormationReview;
import com.project.souklab.model.FormationReviewDecision;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.security.AccessControlService;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service managing administrative moderation of formations, review decisions,
 * publishing workflows, and notification dispatches to instructors.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminFormationService {

    private final AccessControlService accessControlService;

    private final FormationRepository formationRepository;
    private final FormationReviewRepository formationReviewRepository;
    private final FormationFileRepository formationFileRepository;
    private final FormationEnrollmentRepository formationEnrollmentRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AppStorageProperties appStorageProperties;
    private final Clock clock;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    /**
     * Retrieves a paginated review queue of formations awaiting administrative moderation.
     *
     * @param pageable pagination parameters
     * @return paginated response of formations pending review
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<FormationSummaryDTO> getPendingFormations(Pageable pageable) {
        Page<Formation> page = formationRepository.findByStatusAndDeletedAtIsNull(
                FormationStatus.PENDING_REVIEW, pageable);
        return PaginatedResponse.from(page.map(this::mapToSummaryDTO));
    }

    /**
     * Retrieves full formation details for administrative moderation and review.
     *
     * @param id formation unique identifier
     * @return complete formation response DTO
     * @throws ResourceNotFoundException if formation is not found
     */
    @Transactional(readOnly = true)
    public FormationResponseDTO getFormationById(String id) {
        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Formation not found with id: " + id));
        return mapToResponseDTO(formation);
    }

    /**
     * Records an administrative moderation review decision (approval or rejection) for a formation.
     *
     * @param id formation unique identifier
     * @param dto review decision payload
     * @return updated formation response DTO
     */
    @Transactional
    public FormationResponseDTO reviewFormation(String id, FormationReviewRequestDTO dto) {
        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Formation not found with id: " + id));

        if (formation.getStatus() != FormationStatus.PENDING_REVIEW) {
            throw new ConflictException("Formation is not pending review: current status is " + formation.getStatus());
        }

        if (dto.getDecision() == FormationReviewDecision.REJECTED
                && (dto.getComment() == null || dto.getComment().isBlank())) {
            throw new BadRequestException("Review comment is required when rejecting a formation.");
        }

        User admin = resolveAuthenticatedAdmin();

        FormationReview review = FormationReview.builder()
                .formation(formation)
                .admin(admin)
                .decision(dto.getDecision())
                .comment(dto.getComment() != null ? dto.getComment().trim() : null)
                .reviewedAt(LocalDateTime.now(clock))
                .build();
        formationReviewRepository.save(review);

        if (dto.getDecision() == FormationReviewDecision.APPROVED) {
            formation.setStatus(FormationStatus.APPROVED);
        } else {
            formation.setStatus(FormationStatus.REJECTED);
        }
        Formation saved = formationRepository.save(formation);
        if (activityEventService != null) activityEventService.record(
                (dto.getDecision() == FormationReviewDecision.APPROVED
                        ? AnalyticsEvent.Formation.Moderation.APPROVED
                        : AnalyticsEvent.Formation.Moderation.REJECTED), admin.getId(), saved.getId(),
                Map.of(AnalyticsMetadata.Moderation.Decision.VALUE, dto.getDecision()));

        dispatchReviewNotification(saved, dto);
        log.info("Admin '{}' reviewed formation '{}' with decision '{}'", admin.getEmail(), saved.getId(), dto.getDecision());

        return mapToResponseDTO(saved);
    }

    /**
     * Publishes an approved formation to the public catalog for peer artisan enrollment.
     *
     * @param id formation unique identifier
     * @return updated formation response DTO
     */
    @Transactional
    public FormationResponseDTO publishFormation(String id) {
        Formation formation = formationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Formation not found with id: " + id));

        if (formation.getStatus() != FormationStatus.APPROVED) {
            throw new ConflictException("Only approved formations can be published: current status is " + formation.getStatus());
        }

        formation.setStatus(FormationStatus.PUBLISHED);
        Formation saved = formationRepository.save(formation);
        if (activityEventService != null) activityEventService.record(AnalyticsEvent.Formation.PUBLISHED,
                resolveAuthenticatedAdmin().getId(), saved.getId(), Map.of());
        log.info("Formation '{}' published by administrator", saved.getId());

        return mapToResponseDTO(saved);
    }

    /**
     * Dispatches notification to authoring artisan upon moderation decision.
     *
     * @param formation the evaluated formation
     * @param dto review request DTO
     */
    private void dispatchReviewNotification(Formation formation, FormationReviewRequestDTO dto) {
        User authorUser = formation.getAuthor().getUser();
        if (authorUser == null) {
            return;
        }

        if (dto.getDecision() == FormationReviewDecision.APPROVED) {
            String message = "Your formation '" + formation.getTitle() + "' has been approved!";
            notificationService.createForUser(authorUser, message, NotificationType.Formation.APPROVED, formation.getId());
        } else {
            String message = "Your formation '" + formation.getTitle() + "' was rejected: " + dto.getComment().trim();
            notificationService.createForUser(authorUser, message, NotificationType.Formation.REJECTED, formation.getId());
        }
    }

    /**
     * Resolves authenticated administrator from security context.
     *
     * @return authenticated User entity with admin authority
     */
    private User resolveAuthenticatedAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getName())) {
            throw new UnauthorizedException("User is not authenticated");
        }

        if (!accessControlService.canManageFormations(authentication)) {
            throw new ForbiddenException("Access denied: administrator permission required.");
        }

        String username = SecurityUtils.getCurrentUsername();
        if (username == null) {
            throw new UnauthorizedException("User is not authenticated");
        }

        return userRepository.findByEmail(username)
                .orElseThrow(() -> new ResourceNotFoundException("Admin user not found: " + username));
    }

    /**
     * Maps a Formation entity to a full FormationResponseDTO.
     *
     * @param formation the formation entity
     * @return populated response DTO
     */
    private FormationResponseDTO mapToResponseDTO(Formation formation) {
        List<FormationFile> activeFiles = formationFileRepository.findByFormationIdAndDeletedAtIsNull(formation.getId());
        List<FormationReview> reviews = formationReviewRepository.findByFormationIdOrderByReviewedAtDesc(formation.getId());
        long activeEnrollments = formationEnrollmentRepository.countByFormationIdAndStatus(formation.getId(), EnrollmentStatus.CONFIRMED);
        return FormationResponseDTO.from(formation, activeFiles, reviews, activeEnrollments, appStorageProperties.resolveFileServingPrefix());
    }

    /**
     * Maps a Formation entity to a lightweight FormationSummaryDTO.
     *
     * @param formation the formation entity
     * @return populated summary DTO
     */
    private FormationSummaryDTO mapToSummaryDTO(Formation formation) {
        long activeEnrollments = formationEnrollmentRepository.countByFormationIdAndStatus(formation.getId(), EnrollmentStatus.CONFIRMED);
        return FormationSummaryDTO.from(formation, activeEnrollments);
    }
}
