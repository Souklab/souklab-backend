package com.project.souklab.dto.review;

import java.util.Objects;
import java.util.stream.Stream;

import com.project.souklab.model.ArtisanReview;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Public representation of an artisan review.
 */
@Value
@Builder
public class ArtisanReviewResponseDTO {
    String id;
    String reviewerId;
    String reviewerName;
    String artisanId;
    String formationId;
    BigDecimal rating;
    String comment;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    /**
     * Maps an entity to a response.
     *
     * @param review review entity
     * @return response DTO
     */
    public static ArtisanReviewResponseDTO from(ArtisanReview review) {
        String name = Stream.of(review.getReviewer().getUser().getFirstName(), review.getReviewer().getUser().getLastName())
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
        return ArtisanReviewResponseDTO.builder()
                .id(review.getId())
                .reviewerId(review.getReviewer().getId())
                .reviewerName(name)
                .artisanId(review.getArtisan().getId())
                .formationId(review.getEnrollment().getFormation().getId())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}
