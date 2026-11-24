package com.project.souklab.dto;

import com.project.souklab.dto.review.ArtisanReviewResponseDTO;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanReview;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationEnrollment;
import com.project.souklab.model.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtisanReviewResponseDTOTest {

    @Test
    void mapsReviewerNameForAllNameCombinations() {
        assertThat(map("First", "Last").getReviewerName()).isEqualTo("First Last");
        assertThat(map("First", null).getReviewerName()).isEqualTo("First");
        assertThat(map(null, "Last").getReviewerName()).isEqualTo("Last");
        assertThat(map(" ", " ").getReviewerName()).isNull();
    }

    private ArtisanReviewResponseDTO map(String firstName, String lastName) {
        User reviewerUser = User.builder().email("reviewer@example.test").firstName(firstName).lastName(lastName).build();
        Artisan reviewer = Artisan.builder().id("reviewer").user(reviewerUser).build();
        Artisan artisan = Artisan.builder().id("artisan").build();
        Formation formation = Formation.builder().build();
        formation.setId("formation");
        FormationEnrollment enrollment = FormationEnrollment.builder().formation(formation).build();
        ArtisanReview review = ArtisanReview.builder().reviewer(reviewer).artisan(artisan).enrollment(enrollment).build();
        review.setId("review");
        return ArtisanReviewResponseDTO.from(review);
    }
}
