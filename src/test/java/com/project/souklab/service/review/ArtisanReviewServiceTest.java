package com.project.souklab.service.review;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.security.Permission;

import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ArtisanReviewRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dto.review.ArtisanReviewRequestDTO;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.ReviewStatus;
import com.project.souklab.model.ArtisanReview;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies review eligibility, duplicate prevention, and rating aggregation behavior.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanReviewServiceTest {

    @Mock private ArtisanReviewRepository reviewRepository;
    @Mock private FormationEnrollmentRepository enrollmentRepository;
    @Mock private ArtisanRepository artisanRepository;
    @Mock private NotificationService notificationService;

    private ArtisanReviewService service;
    private Artisan reviewer;
    private Artisan subject;
    private Formation formation;
    private FormationEnrollment enrollment;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new ArtisanReviewService(reviewRepository, enrollmentRepository, artisanRepository, notificationService, clock);
        User reviewerUser = User.builder().email("reviewer@example.com").firstName("A").lastName("Reviewer").build();
        reviewerUser.setId("reviewer-user");
        reviewer = Artisan.builder().id("reviewer").user(reviewerUser).build();
        User subjectUser = User.builder().email("subject@example.com").firstName("B").lastName("Subject").build();
        subjectUser.setId("subject-user");
        subject = Artisan.builder().id("subject").user(subjectUser).build();
        formation = Formation.builder().author(subject).status(FormationStatus.COMPLETED).build();
        formation.setId("formation");
        enrollment = FormationEnrollment.builder()
                .formation(formation).artisan(reviewer).status(EnrollmentStatus.ATTENDED).build();
        enrollment.setId("enrollment");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "reviewer@example.com", "credentials", List.of(Permission.Artisan.REVIEWS)));
        when(artisanRepository.findByUserEmailIgnoreCase("reviewer@example.com")).thenReturn(Optional.of(reviewer));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsReviewWithoutChangingProfileAggregate() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        when(reviewRepository.findByEnrollmentId("enrollment")).thenReturn(Optional.empty());
        when(reviewRepository.save(any(ArtisanReview.class))).thenAnswer(invocation -> {
            ArtisanReview review = invocation.getArgument(0);
            review.setId("review");
            return review;
        });
        var result = service.create("formation", new ArtisanReviewRequestDTO(new BigDecimal("4.2"), "Excellent"));

        assertThat(result.getRating()).isEqualByComparingTo("4.20");
        assertThat(subject.getRating()).isZero();
        assertThat(subject.getReviewsCount()).isZero();
        verify(artisanRepository, org.mockito.Mockito.never()).save(subject);
    }

    @Test
    void rejectsDuplicateReview() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        when(reviewRepository.findByEnrollmentId("enrollment")).thenReturn(Optional.of(new ArtisanReview()));

        assertThatThrownBy(() -> service.create("formation", new ArtisanReviewRequestDTO(BigDecimal.ONE, "Duplicate")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void recreatesReviewWhenPreviouslyDeleted() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        ArtisanReview softDeleted = ArtisanReview.builder()
                .reviewer(reviewer)
                .artisan(subject)
                .enrollment(enrollment)
                .rating(new BigDecimal("2.00"))
                .comment("Old removed review")
                .status(ReviewStatus.REMOVED)
                .build();
        softDeleted.setId("review-old");
        softDeleted.setDeletedAt(LocalDateTime.now());

        when(reviewRepository.findByEnrollmentId("enrollment")).thenReturn(Optional.of(softDeleted));
        when(reviewRepository.save(any(ArtisanReview.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create("formation", new ArtisanReviewRequestDTO(new BigDecimal("5.0"), "Much better now"));

        assertThat(result.getId()).isEqualTo("review-old");
        assertThat(result.getRating()).isEqualByComparingTo("5.00");
        assertThat(result.getComment()).isEqualTo("Much better now");
        assertThat(softDeleted.getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(softDeleted.getDeletedAt()).isNull();
        verify(artisanRepository, org.mockito.Mockito.never()).save(subject);
    }

    @Test
    void rejectsReviewBeforeAttendance() {
        enrollment.setStatus(EnrollmentStatus.CONFIRMED);
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));

        assertThatThrownBy(() -> service.create("formation", new ArtisanReviewRequestDTO(BigDecimal.ONE, "Too early")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listsAndUpdatesOwnedReviewWithRoundedRating() {
        ArtisanReview review = ArtisanReview.builder().reviewer(reviewer).artisan(subject)
                .enrollment(enrollment).rating(new BigDecimal("3.50")).comment("old").status(ReviewStatus.PUBLISHED).build();
        review.setId("review");
        assertThat(service.list("subject", PageRequest.of(0, 10))).isEmpty();
        when(reviewRepository.findByEnrollment_Formation_IdAndStatusAndDeletedAtIsNull("formation", ReviewStatus.PUBLISHED, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(review)));
        assertThat(service.listForFormation("formation", PageRequest.of(0, 10))).hasSize(1);
        when(reviewRepository.findByIdAndDeletedAtIsNull("review")).thenReturn(Optional.of(review));
        when(reviewRepository.save(any(ArtisanReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.update("review", new ArtisanReviewRequestDTO(new BigDecimal("4.126"), " changed "));
        assertThat(result.getRating()).isEqualByComparingTo("4.13");
        assertThat(review.getComment()).isEqualTo("changed");
        assertThat(subject.getRating()).isZero();
        assertThat(subject.getReviewsCount()).isZero();
    }

    @Test
    void deletesOwnedReviewAndRejectsForeignOrRemovedEdits() {
        ArtisanReview review = ArtisanReview.builder().reviewer(reviewer).artisan(subject).enrollment(enrollment)
                .rating(BigDecimal.ONE).comment("old").status(ReviewStatus.PUBLISHED).build();
        review.setId("review");
        when(reviewRepository.findByIdAndDeletedAtIsNull("review")).thenReturn(Optional.of(review));
        service.delete("review");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.REMOVED);
        assertThat(review.getDeletedAt()).isNotNull();
        assertThat(subject.getRating()).isZero();
        assertThat(subject.getReviewsCount()).isZero();

        Artisan other = Artisan.builder().id("other").build();
        review.setStatus(ReviewStatus.PUBLISHED); review.setReviewer(other);
        assertThatThrownBy(() -> service.update("review", new ArtisanReviewRequestDTO(BigDecimal.TEN, "x")))
                .isInstanceOf(ForbiddenException.class);
        review.setReviewer(reviewer); review.setStatus(ReviewStatus.REMOVED);
        assertThatThrownBy(() -> service.update("review", new ArtisanReviewRequestDTO(BigDecimal.TEN, "x")))
                .isInstanceOf(ConflictException.class);

        review.setStatus(ReviewStatus.PUBLISHED);
        review.setReviewer(other);
        assertThatThrownBy(() -> service.delete("review"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsMissingReviewsForUpdateAndDelete() {
        when(reviewRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("missing", new ArtisanReviewRequestDTO(BigDecimal.ONE, "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsMissingEnrollmentSelfReviewAndExistingReview() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("missing", "reviewer")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create("missing", new ArtisanReviewRequestDTO(BigDecimal.ONE, "x")))
                .isInstanceOf(ForbiddenException.class);
        formation.setAuthor(reviewer);
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        assertThatThrownBy(() -> service.create("formation", new ArtisanReviewRequestDTO(BigDecimal.ONE, "x")))
                .isInstanceOf(ConflictException.class);
        formation.setAuthor(subject);
        when(reviewRepository.findByEnrollmentId("enrollment")).thenReturn(Optional.of(new ArtisanReview()));
        assertThatThrownBy(() -> service.create("formation", new ArtisanReviewRequestDTO(BigDecimal.ONE, "x")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getMyReview_whenReviewActive_returnsDto() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        ArtisanReview review = ArtisanReview.builder()
                .rating(BigDecimal.valueOf(4.5))
                .comment("Excellent session")
                .status(ReviewStatus.PUBLISHED)
                .reviewer(reviewer)
                .artisan(subject)
                .enrollment(enrollment)
                .build();
        review.setId("rev-1");
        when(reviewRepository.findByEnrollmentIdAndDeletedAtIsNull("enrollment")).thenReturn(Optional.of(review));

        var result = service.getMyReview("formation");

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("rev-1");
        assertThat(result.getComment()).isEqualTo("Excellent session");
    }

    @Test
    void getMyReview_whenReviewSoftDeletedOrAbsent_returnsNull() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("formation", "reviewer")).thenReturn(Optional.of(enrollment));
        when(reviewRepository.findByEnrollmentIdAndDeletedAtIsNull("enrollment")).thenReturn(Optional.empty());

        var result = service.getMyReview("formation");

        assertThat(result).isNull();
    }

    @Test
    void getMyReview_whenEnrollmentNotFound_throwsResourceNotFoundException() {
        when(enrollmentRepository.findByFormationIdAndArtisanId("missing", "reviewer")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyReview("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Enrollment not found.");
    }
}
