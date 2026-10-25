package com.project.souklab.controller.formation;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formation.FormationCreateDTO;
import com.project.souklab.dto.formation.FormationFileResponseDTO;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.dto.formation.FormationUpdateDTO;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.service.formation.FormationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link ArtisanFormationController}.
 * Verifies endpoint routing, request mapping, validation annotations,
 * and role-based access control (@PreAuthorize("hasRole('ARTISAN')")).
 */
@ControllerSliceTest(controllers = ArtisanFormationController.class)
class ArtisanFormationControllerTest {

    private static final String BASE_URL = "/api/v1/artisan/formations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FormationService formationService;

    @Nested
    @DisplayName("Create Formation (POST /api/v1/artisan/formations)")
    class CreateFormationEndpointTests {

        /**
         * Verifies that an authenticated artisan can create a formation draft and receive 201 Created.
         */
        @Test
        @DisplayName("createFormation_whenArtisanRole_shouldReturn201Created")
        void createFormation_whenArtisanRole_shouldReturn201Created() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .title("Wood Carving Workshop")
                    .status(FormationStatus.DRAFT)
                    .price(8000)
                    .currency("DZD")
                    .build();

            when(formationService.createFormation(any(FormationCreateDTO.class))).thenReturn(responseDTO);

            String requestJson = """
                    {
                        "title": "Wood Carving Workshop",
                        "description": "Learn traditional Algerian wood carving techniques",
                        "location": "Algiers",
                        "isOnline": false,
                        "scheduledAt": "2030-01-01T10:00:00",
                        "durationHours": 5,
                        "maxParticipants": 10,
                        "price": 8000,
                        "currency": "DZD"
                    }
                    """;

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(artisan()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.data.id").value("formation-101"))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));
        }

        /**
         * Verifies that a client user receives 403 Forbidden.
         */
        @Test
        @DisplayName("createFormation_whenClientRole_shouldReturn403Forbidden")
        void createFormation_whenClientRole_shouldReturn403Forbidden() throws Exception {
            String requestJson = """
                    {
                        "title": "Wood Carving Workshop",
                        "description": "Description",
                        "isOnline": false,
                        "scheduledAt": "2030-01-01T10:00:00",
                        "durationHours": 5,
                        "maxParticipants": 10,
                        "price": 8000
                    }
                    """;

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Update Formation (PUT /api/v1/artisan/formations/{id})")
    class UpdateFormationEndpointTests {

        /**
         * Verifies that an authenticated artisan can update a formation and receive 200 OK.
         */
        @Test
        @DisplayName("updateFormation_whenArtisanRole_shouldReturn200Ok")
        void updateFormation_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .title("Updated Wood Carving")
                    .status(FormationStatus.DRAFT)
                    .build();

            when(formationService.updateFormation(eq("formation-101"), any(FormationUpdateDTO.class)))
                    .thenReturn(responseDTO);

            String requestJson = """
                    {
                        "title": "Updated Wood Carving",
                        "description": "Updated syllabus description",
                        "isOnline": false,
                        "scheduledAt": "2030-01-05T10:00:00",
                        "durationHours": 6,
                        "maxParticipants": 12,
                        "price": 9000
                    }
                    """;

            mockMvc.perform(put(BASE_URL + "/formation-101")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson)
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.title").value("Updated Wood Carving"));
        }
    }

    @Nested
    @DisplayName("Media and Files Uploads")
    class MediaAndFilesEndpointTests {

        /**
         * Verifies uploading a thumbnail image returns 200 OK.
         */
        @Test
        @DisplayName("uploadThumbnail_whenArtisanRole_shouldReturn200Ok")
        void uploadThumbnail_whenArtisanRole_shouldReturn200Ok() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "thumb.jpg", "image/jpeg", "image".getBytes());

            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .thumbnailUrl("/api/v1/files/thumb-key")
                    .build();

            when(formationService.uploadThumbnail(eq("formation-101"), any())).thenReturn(responseDTO);

            mockMvc.perform(multipart(BASE_URL + "/formation-101/thumbnail")
                            .file(file)
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.thumbnailUrl").value("/api/v1/files/thumb-key"));
        }

        /**
         * Verifies uploading a course attachment file returns 201 Created.
         */
        @Test
        @DisplayName("uploadCourseFile_whenArtisanRole_shouldReturn201Created")
        void uploadCourseFile_whenArtisanRole_shouldReturn201Created() throws Exception {
            MockMultipartFile file = new MockMultipartFile("file", "syllabus.pdf", "application/pdf", "pdf".getBytes());

            FormationFileResponseDTO fileDTO = FormationFileResponseDTO.builder()
                    .id("file-501")
                    .originalFilename("syllabus.pdf")
                    .downloadUrl("/api/v1/files/file-key")
                    .build();

            when(formationService.uploadCourseFile(eq("formation-101"), any())).thenReturn(fileDTO);

            mockMvc.perform(multipart(BASE_URL + "/formation-101/files")
                            .file(file)
                            .with(artisan()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.data.id").value("file-501"));
        }

        /**
         * Verifies deleting a course attachment returns 200 OK.
         */
        @Test
        @DisplayName("deleteCourseFile_whenArtisanRole_shouldReturn200Ok")
        void deleteCourseFile_whenArtisanRole_shouldReturn200Ok() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/formation-101/files/file-501")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(formationService).deleteCourseFile("formation-101", "file-501");
        }
    }

    @Nested
    @DisplayName("Review Submission and Lifecycle")
    class ReviewSubmissionEndpointTests {

        /**
         * Verifies submitting a formation for review returns 200 OK.
         */
        @Test
        @DisplayName("submitForReview_whenArtisanRole_shouldReturn200Ok")
        void submitForReview_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .status(FormationStatus.PENDING_REVIEW)
                    .build();

            when(formationService.submitForReview("formation-101")).thenReturn(responseDTO);

            mockMvc.perform(post(BASE_URL + "/formation-101/submit")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        }

        /**
         * Verifies deleting a formation returns 200 OK.
         */
        @Test
        @DisplayName("deleteFormation_whenArtisanRole_shouldReturn200Ok")
        void deleteFormation_whenArtisanRole_shouldReturn200Ok() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/formation-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(formationService).deleteFormation("formation-101");
        }

        /**
         * Verifies getMyFormations returns 200 OK with paginated list.
         */
        @Test
        @DisplayName("getMyFormations_whenArtisanRole_shouldReturn200Ok")
        void getMyFormations_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationSummaryDTO summary = FormationSummaryDTO.builder()
                    .id("formation-101")
                    .title("Wood Carving Workshop")
                    .status(FormationStatus.DRAFT)
                    .build();

            when(formationService.getMyFormations(any()))
                    .thenReturn(PaginatedResponse.from(new PageImpl<>(List.of(summary))));

            mockMvc.perform(get(BASE_URL + "/me")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value("formation-101"));
        }

        /**
         * Verifies cancelling a formation returns 200 OK.
         */
        @Test
        @DisplayName("cancelFormation_whenArtisanRole_shouldReturn200Ok")
        void cancelFormation_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .title("Wood Carving Workshop")
                    .status(FormationStatus.CANCELLED)
                    .build();

            when(formationService.cancelFormation("formation-101")).thenReturn(responseDTO);

            mockMvc.perform(post(BASE_URL + "/formation-101/cancel-formation")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("formation-101"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));

            verify(formationService).cancelFormation("formation-101");
        }
    }
}
