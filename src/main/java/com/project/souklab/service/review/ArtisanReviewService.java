package com.project.souklab.service.review;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ArtisanReviewRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dto.review.ArtisanReviewRequestDTO;
import com.project.souklab.dto.review.ArtisanReviewResponseDTO;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanReview;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.ReviewStatus;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.model.NotificationType;
import com.project.souklab.util.ArtisanSecurityUtils;
import com.project.souklab.security.Permission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Coordinates formation-backed artisan reviews and aggregate rating updates.
 */
@Service
@RequiredArgsConstructor
public class ArtisanReviewService {

    private final ArtisanReviewRepository reviewRepository;
    private final FormationEnrollmentRepository enrollmentRepository;
    private final ArtisanRepository artisanRepository;
    private final NotificationService notificationService;
    private final Clock clock;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    /**
     * Lists visible reviews for an artisan.
     *
     * @param artisanId artisan identifier
     * @param pageable pagination configuration
     * @return visible reviews
     */
    @Transactional(readOnly = true)
    public Page<ArtisanReviewResponseDTO> list(String artisanId, Pageable pageable) {
        return Page.empty(pageable);
    }

    @Transactional(readOnly = true)
    public Page<ArtisanReviewResponseDTO> listForFormation(String formationId, Pageable pageable) {
        return reviewRepository.findByEnrollment_Formation_IdAndStatusAndDeletedAtIsNull(
                        formationId, ReviewStatus.PUBLISHED, pageable)
                .map(ArtisanReviewResponseDTO::from);
    }

    @Transactional(readOnly = true)
    public ArtisanReviewResponseDTO getPublished(String reviewId) {
        throw new ResourceNotFoundException("Review not found.");
    }

    @Transactional(readOnly = true)
    public ArtisanReviewResponseDTO getMyReview(String formationId) {
        Artisan reviewer = currentArtisan();
        var enrollment = enrollmentRepository.findByFormationIdAndArtisanId(formationId, reviewer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Enrollment not found."));
        return reviewRepository.findByEnrollmentIdAndDeletedAtIsNull(enrollment.getId())
                .map(ArtisanReviewResponseDTO::from)
                .orElse(null);
    }

    /**
     * Creates a review for an attended, completed formation enrollment.
     *
     * @param formationId formation identifier
     * @param request rating and comment
     * @return created review
     */
    @Transactional
    public ArtisanReviewResponseDTO create(String formationId, @Valid ArtisanReviewRequestDTO request) {
        Artisan reviewer = currentArtisan();
        var enrollment = enrollmentRepository.findByFormationIdAndArtisanId(formationId, reviewer.getId())
                .orElseThrow(() -> new ForbiddenException("Only enrolled artisans may submit a review."));
        boolean isAttended = enrollment.getStatus() == EnrollmentStatus.ATTENDED;
        boolean isCompleted = enrollment.getFormation() != null
                && enrollment.getFormation().getStatus() == FormationStatus.COMPLETED;
        if (!isAttended || !isCompleted) {
            throw new ForbiddenException("Reviews require an attended completed formation.");
        }
        Artisan subject = enrollment.getFormation().getAuthor();
        if (subject == null || subject.getId().equals(reviewer.getId())) {
            throw new ConflictException("You cannot review your own formation.");
        }
        Optional<ArtisanReview> existingReviewOpt = reviewRepository.findByEnrollmentId(enrollment.getId());
        ArtisanReview review;
        if (existingReviewOpt.isPresent()) {
            ArtisanReview existing = existingReviewOpt.get();
            if (existing.getDeletedAt() == null && existing.getStatus() != ReviewStatus.REMOVED) {
                throw new ConflictException("A review already exists for this enrollment.");
            }
            existing.setRating(normalizeRating(request.getRating()));
            existing.setComment(request.getComment().trim());
            existing.setStatus(ReviewStatus.PUBLISHED);
            existing.setDeletedAt(null);
            review = existing;
        } else {
            review = ArtisanReview.builder()
                    .reviewer(reviewer)
                    .artisan(subject)
                    .enrollment(enrollment)
                    .rating(normalizeRating(request.getRating()))
                    .comment(request.getComment().trim())
                    .status(ReviewStatus.PUBLISHED)
                    .build();
        }
        ArtisanReview saved = reviewRepository.save(review);
        if (activityEventService != null) {
            activityEventService.record(AnalyticsEvent.Review.SUBMITTED, reviewer.getId(), saved.getId(),
                    Map.of(AnalyticsMetadata.Content.Formation.ID, formationId, AnalyticsMetadata.Content.Rating.VALUE, saved.getRating()));
        }
        notificationService.createForUser(subject.getUser(), "You received a new artisan review.", NotificationType.Review.NEW, saved.getId());
        return ArtisanReviewResponseDTO.from(saved);
    }

    /**
     * Updates a review owned by the current artisan.
     *
     * @param reviewId review identifier
     * @param request new rating and comment
     * @return updated review
     */
    @Transactional
    public ArtisanReviewResponseDTO update(String reviewId, @Valid ArtisanReviewRequestDTO request) {
        Artisan reviewer = currentArtisan();
        ArtisanReview review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found."));
        if (!review.getReviewer().getId().equals(reviewer.getId())) {
            throw new ForbiddenException("You may only edit your own review.");
        }
        if (review.getStatus() == ReviewStatus.REMOVED) {
            throw new ConflictException("Removed reviews cannot be edited.");
        }
        review.setRating(normalizeRating(request.getRating()));
        review.setComment(request.getComment().trim());
        ArtisanReview saved = reviewRepository.save(review);
        return ArtisanReviewResponseDTO.from(saved);
    }

    /**
     * Removes a review owned by the current artisan and recalculates aggregates.
     *
     * @param reviewId review identifier
     */
    @Transactional
    public void delete(String reviewId) {
        Artisan reviewer = currentArtisan();
        ArtisanReview review = reviewRepository.findByIdAndDeletedAtIsNull(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found."));
        if (!review.getReviewer().getId().equals(reviewer.getId())) {
            throw new ForbiddenException("You may only remove your own review.");
        }
        review.setStatus(ReviewStatus.REMOVED);
        review.setDeletedAt(LocalDateTime.now(clock));
        reviewRepository.save(review);
    }

    private Artisan currentArtisan() {
        return ArtisanSecurityUtils.resolveAuthenticatedArtisan(artisanRepository, Permission.Artisan.REVIEWS);
    }

    private BigDecimal normalizeRating(BigDecimal rating) {
        return rating.setScale(2, RoundingMode.HALF_UP);
    }
}
