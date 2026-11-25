package com.project.souklab.controller.review;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.review.ArtisanReviewResponseDTO;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.security.Permission;
import com.project.souklab.service.review.ArtisanReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = ArtisanReviewController.class)
class ArtisanReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanReviewService reviewService;

    @Test
    void publicUserCanListReviews() throws Exception {
        when(reviewService.list(eq("artisan-1"), any())).thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/v1/artisans/artisan-1/reviews").with(client()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void formationReviewsAreVisibleOnlyToArtisansFormateursAndAdmins() throws Exception {
        when(reviewService.listForFormation(eq("formation-1"), any())).thenReturn(
                new PageImpl<>(List.of(ArtisanReviewResponseDTO.builder()
                        .id("review-1").reviewerName("Reviewer").formationId("formation-1").build())));

        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews").with(client()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews")
                        .with(SecurityMockMvcRequestPostProcessors.user("premium-client")
                                .authorities(Permission.Client.FAVORITES, Permission.Message.SEND)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reviewerName").value("Reviewer"))
                .andExpect(jsonPath("$.data.content[0].formationId").value("formation-1"));
        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews")
                        .with(SecurityMockMvcRequestPostProcessors.user("formateur")
                                .authorities(Permission.Artisan.FORMATIONS, Permission.Artisan.CONTENT)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/artisan/formations/formation-1/reviews").with(admin()))
                .andExpect(status().isOk());
        verify(reviewService, times(3)).listForFormation(eq("formation-1"), any());
    }

    @Test
    void publishedFormationReviewIdsAreHiddenFromPublicReviewRoute() throws Exception {
        when(reviewService.getPublished("formation-review-1"))
                .thenThrow(new ResourceNotFoundException("Review not found."));

        mockMvc.perform(get("/api/v1/artisan/reviews/formation-review-1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/artisan/reviews/formation-review-1").with(client()))
                .andExpect(status().isNotFound());
    }

    @Test
    void authorizedArtisanCanCreateUpdateAndDeleteReview() throws Exception {
        when(reviewService.create(eq("formation-1"), any())).thenReturn(ArtisanReviewResponseDTO.builder().id("review-1").build());
        when(reviewService.update(eq("review-1"), any())).thenReturn(ArtisanReviewResponseDTO.builder().id("review-1").build());

        String request = "{\"rating\":4.50,\"comment\":\"Excellent workshop\"}";
        mockMvc.perform(post("/api/v1/artisan/formations/formation-1/reviews")
                        .with(artisan()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("review-1"));
        mockMvc.perform(put("/api/v1/artisan/reviews/review-1")
                        .with(artisan()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("review-1"));
        mockMvc.perform(delete("/api/v1/artisan/reviews/review-1").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void clientCannotManageArtisanReviews() throws Exception {
        mockMvc.perform(post("/api/v1/artisan/formations/formation-1/reviews")
                        .with(client()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"comment\":\"No access\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidReviewPayloadIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/artisan/formations/formation-1/reviews")
                        .with(artisan()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":6,\"comment\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
