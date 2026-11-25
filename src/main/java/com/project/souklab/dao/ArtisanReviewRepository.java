package com.project.souklab.dao;
import java.time.LocalDateTime;

import com.project.souklab.model.ReviewStatus;

import com.project.souklab.model.ArtisanReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Persistence operations for formation-backed artisan reviews.
 */
public interface ArtisanReviewRepository extends JpaRepository<ArtisanReview, String> {
    long countByStatusAndDeletedAtIsNull(ReviewStatus status);
    long countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(ReviewStatus status, LocalDateTime from, LocalDateTime to);
    long countByCreatedAtBetweenAndDeletedAtIsNull(LocalDateTime from, LocalDateTime to);
    @Query("select avg(r.rating) from ArtisanReview r where r.status = :status and r.deletedAt is null")
    BigDecimal averageRatingByStatus(@Param("status") ReviewStatus status);
    @Query("select avg(r.rating) from ArtisanReview r where r.status = :status and r.createdAt >= :from and r.createdAt <= :to and r.deletedAt is null")
    BigDecimal averageRatingByStatusAndCreatedAtBetweenAndDeletedAtIsNull(@Param("status") ReviewStatus status,
                                                                            @Param("from") LocalDateTime from,
                                                                            @Param("to") LocalDateTime to);
    Optional<ArtisanReview> findByIdAndDeletedAtIsNull(String id);
    Page<ArtisanReview> findByArtisanIdAndStatusAndDeletedAtIsNull(String artisanId, ReviewStatus status, Pageable pageable);
    Optional<ArtisanReview> findByIdAndStatusAndDeletedAtIsNull(String id, ReviewStatus status);
    Optional<ArtisanReview> findByEnrollmentId(String enrollmentId);
    Optional<ArtisanReview> findByEnrollmentIdAndDeletedAtIsNull(String enrollmentId);
    Page<ArtisanReview> findByEnrollment_Formation_IdAndStatusAndDeletedAtIsNull(
            String formationId, ReviewStatus status, Pageable pageable);

    @Query("select avg(r.rating) from ArtisanReview r where r.artisan.id = :artisanId and r.status = :status and r.deletedAt is null")
    BigDecimal averageRating(@Param("artisanId") String artisanId, @Param("status") ReviewStatus status);

    long countByArtisanIdAndStatusAndDeletedAtIsNull(String artisanId, ReviewStatus status);
}
