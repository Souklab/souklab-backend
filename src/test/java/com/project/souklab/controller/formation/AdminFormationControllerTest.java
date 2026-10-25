package com.project.souklab.controller.formation;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationReviewRequestDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.service.formation.AdminFormationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link AdminFormationController}.
 * Verifies review queue retrieval, moderation decisions, publishing,
 * and role-based access control (@PreAuthorize("hasRole('ADMIN')")).
 */
@ControllerSliceTest(controllers = AdminFormationController.class)
class AdminFormationControllerTest {

    private static final String BASE_URL = "/api/v1/admin/formations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminFormationService adminFormationService;

    @Nested
    @DisplayName("Review Queue (GET /api/v1/admin/formations/pending)")
    class PendingQueueEndpointTests {

        /**
         * Verifies that an authenticated admin can access the review queue.
         */
        @Test
        @DisplayName("getPendingFormations_whenAdminRole_shouldReturn200Ok")
        void getPendingFormations_whenAdminRole_shouldReturn200Ok() throws Exception {
            FormationSummaryDTO summary = FormationSummaryDTO.builder()
                    .id("formation-201")
                    .title("Copper Engraving")
                    .status(FormationStatus.PENDING_REVIEW)
                    .build();

            when(adminFormationService.getPendingFormations(any()))
                    .thenReturn(PaginatedResponse.from(new PageImpl<>(List.of(summary))));

            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value("formation-201"))
                    .andExpect(jsonPath("$.data.content[0].status").value("PENDING_REVIEW"));
        }

        /**
         * Verifies that an artisan receives 403 Forbidden on admin review queue.
         */
        @Test
        @DisplayName("getPendingFormations_whenArtisanRole_shouldReturn403Forbidden")
        void getPendingFormations_whenArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(artisan()))
                    .andExpect(status().isForbidden());
        }

        /**
         * Verifies that a client receives 403 Forbidden on admin review queue.
         */
        @Test
        @DisplayName("getPendingFormations_whenClientRole_shouldReturn403Forbidden")
        void getPendingFormations_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/pending")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Single Formation (GET /api/v1/admin/formations/{id})")
    class GetFormationDetailsEndpointTests {

        @Test
        @DisplayName("getFormationById_whenAdminRole_shouldReturn200Ok")
        void getFormationById_whenAdminRole_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-201")
                    .title("Leather Crafting")
                    .status(FormationStatus.PENDING_REVIEW)
                    .build();

            when(adminFormationService.getFormationById("formation-201")).thenReturn(responseDTO);

            mockMvc.perform(get(BASE_URL + "/formation-201")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("formation-201"))
                    .andExpect(jsonPath("$.data.title").value("Leather Crafting"));
        }

        @Test
        @DisplayName("getFormationById_whenArtisanRole_shouldReturn403Forbidden")
        void getFormationById_whenArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/formation-201")
                            .with(artisan()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Review Decision (POST /api/v1/admin/formations/{id}/review)")
    class ReviewEndpointTests {

        /**
         * Verifies that an administrator can approve a formation and receive 200 OK.
         */
        @Test
        @DisplayName("reviewFormation_whenAdminApproves_shouldReturn200Ok")
        void reviewFormation_whenAdminApproves_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-201")
                    .status(FormationStatus.APPROVED)
                    .build();

            when(adminFormationService.reviewFormation(eq("formation-201"), any(FormationReviewRequestDTO.class)))
                    .thenReturn(responseDTO);

            mockMvc.perform(post(BASE_URL + "/formation-201/review")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVED\",\"comment\":\"Approved by admin\"}")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        /**
         * Verifies that an artisan cannot review formations.
         */
        @Test
        @DisplayName("reviewFormation_whenArtisanRole_shouldReturn403Forbidden")
        void reviewFormation_whenArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post(BASE_URL + "/formation-201/review")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVED\"}")
                            .with(artisan()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Publish Formation (POST /api/v1/admin/formations/{id}/publish)")
    class PublishEndpointTests {

        /**
         * Verifies that an administrator can publish an approved formation and receive 200 OK.
         */
        @Test
        @DisplayName("publishFormation_whenAdminRole_shouldReturn200Ok")
        void publishFormation_whenAdminRole_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-201")
                    .status(FormationStatus.PUBLISHED)
                    .build();

            when(adminFormationService.publishFormation("formation-201")).thenReturn(responseDTO);

            mockMvc.perform(post(BASE_URL + "/formation-201/publish")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }

        /**
         * Verifies that a non-admin receives 403 Forbidden.
         */
        @Test
        @DisplayName("publishFormation_whenArtisanRole_shouldReturn403Forbidden")
        void publishFormation_whenArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post(BASE_URL + "/formation-201/publish")
                            .with(artisan()))
                    .andExpect(status().isForbidden());
        }
    }
}
