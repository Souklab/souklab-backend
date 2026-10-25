package com.project.souklab.controller.formation;
import org.hamcrest.Matchers;


import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.formation.FormationEnrollmentDetailDTO;
import com.project.souklab.dto.formation.FormationEnrollmentResponseDTO;
import com.project.souklab.dto.formation.FormationFileDescriptorDTO;
import com.project.souklab.dto.formation.FormationPublicViewDTO;
import com.project.souklab.dto.formation.FormationResponseDTO;
import com.project.souklab.dto.formation.FormationSummaryDTO;
import com.project.souklab.filestorage.StorageResource;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.service.formation.FormationEnrollmentService;
import com.project.souklab.service.formation.FormationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link ArtisanFormationEnrollmentController}.
 * Verifies route mappings for catalog browsing, masterclass enrollment, cancellation,
 * enrollment history, and protected file downloads with role-based access control.
 */
@ControllerSliceTest(controllers = ArtisanFormationEnrollmentController.class)
class ArtisanFormationEnrollmentControllerTest {

    private static final String BASE_URL = "/api/v1/artisan/formations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FormationEnrollmentService formationEnrollmentService;

    @MockitoBean
    private FormationService formationService;

    @Nested
    @DisplayName("Catalog Endpoints")
    class CatalogEndpointTests {

        /**
         * Verifies that an authenticated artisan can browse the published catalog and receive 200 OK.
         */
        @Test
        @DisplayName("getPublishedCatalog_whenArtisanRole_shouldReturn200Ok")
        void getPublishedCatalog_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationSummaryDTO summary = FormationSummaryDTO.builder()
                    .id("formation-101")
                    .title("Traditional Leather Crafting")
                    .status(FormationStatus.PUBLISHED)
                    .price(12000)
                    .currency("DZD")
                    .activeEnrollmentsCount(3L)
                    .build();

            when(formationEnrollmentService.getPublishedCatalog(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(summary)));

            mockMvc.perform(get(BASE_URL + "/catalog")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].id").value("formation-101"))
                    .andExpect(jsonPath("$.data.content[0].activeEnrollmentsCount").value(3));
        }

        /**
         * Verifies that client role access to the catalog is rejected with 403 Forbidden.
         */
        @Test
        @DisplayName("getPublishedCatalog_whenClientRole_shouldReturn403Forbidden")
        void getPublishedCatalog_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/catalog")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        /**
         * Verifies retrieving published formation details returns 200 OK for artisan.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenArtisanRole_shouldReturn200Ok")
        void getPublishedFormationDetails_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationFileDescriptorDTO fileDTO = FormationFileDescriptorDTO.builder()
                    .id("file-101")
                    .filename("syllabus.pdf")
                    .contentType("application/pdf")
                    .fileSize(1024L)
                    .downloadUrl("/api/v1/artisan/formations/formation-101/files/file-101/download")
                    .build();

            FormationPublicViewDTO viewDTO = FormationPublicViewDTO.builder()
                    .id("formation-101")
                    .title("Traditional Leather Crafting")
                    .maxParticipants(10)
                    .activeEnrollmentsCount(4L)
                    .availableSeats(6L)
                    .isEnrolled(true)
                    .files(List.of(fileDTO))
                    .build();

            when(formationEnrollmentService.getPublishedFormationDetails("formation-101"))
                    .thenReturn(viewDTO);

            mockMvc.perform(get(BASE_URL + "/catalog/formation-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("formation-101"))
                    .andExpect(jsonPath("$.data.availableSeats").value(6))
                    .andExpect(jsonPath("$.data.isEnrolled").value(true))
                    .andExpect(jsonPath("$.data.files[0].downloadUrl").exists());
        }

        /**
         * Verifies that client role access to formation details is rejected with 403 Forbidden.
         */
        @Test
        @DisplayName("getPublishedFormationDetails_whenClientRole_shouldReturn403Forbidden")
        void getPublishedFormationDetails_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/catalog/formation-101")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Enrollment & Cancellation Endpoints")
    class EnrollmentAndCancellationEndpointTests {

        /**
         * Verifies that enrolling in a formation returns 200 OK for an authenticated artisan.
         */
        @Test
        @DisplayName("enroll_whenArtisanRole_shouldReturn200Ok")
        void enroll_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationEnrollmentResponseDTO enrollmentDTO = FormationEnrollmentResponseDTO.builder()
                    .id("enrollment-101")
                    .formationId("formation-101")
                    .formationTitle("Traditional Leather Crafting")
                    .artisanId("artisan-peer-1")
                    .artisanName("Amine Artisan")
                    .status(EnrollmentStatus.CONFIRMED)
                    .enrolledAt(LocalDateTime.now())
                    .build();

            when(formationEnrollmentService.enrollInFormation("formation-101"))
                    .thenReturn(enrollmentDTO);

            mockMvc.perform(post(BASE_URL + "/formation-101/enroll")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("enrollment-101"))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        }

        /**
         * Verifies that enrolling in a formation as client is rejected with 403 Forbidden.
         */
        @Test
        @DisplayName("enroll_whenClientRole_shouldReturn403Forbidden")
        void enroll_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post(BASE_URL + "/formation-101/enroll")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        /**
         * Verifies that cancelling an enrollment returns 200 OK for an authenticated artisan.
         */
        @Test
        @DisplayName("cancel_whenArtisanRole_shouldReturn200Ok")
        void cancel_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationEnrollmentResponseDTO cancelledDTO = FormationEnrollmentResponseDTO.builder()
                    .id("enrollment-101")
                    .formationId("formation-101")
                    .status(EnrollmentStatus.CANCELLED)
                    .cancelledAt(LocalDateTime.now())
                    .build();

            when(formationEnrollmentService.cancelEnrollment("formation-101"))
                    .thenReturn(cancelledDTO);

            mockMvc.perform(post(BASE_URL + "/formation-101/cancel")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        /**
         * Verifies that cancelling as formation author cancels the formation and returns 200 OK.
         */
        @Test
        @DisplayName("cancel_whenAuthorArtisanRole_cancelsFormationAndReturns200Ok")
        void cancel_whenAuthorArtisanRole_cancelsFormationAndReturns200Ok() throws Exception {
            FormationResponseDTO responseDTO = FormationResponseDTO.builder()
                    .id("formation-101")
                    .title("Wood Carving Workshop")
                    .status(FormationStatus.CANCELLED)
                    .build();

            when(formationEnrollmentService.isAuthor("formation-101")).thenReturn(true);
            when(formationService.cancelFormation("formation-101")).thenReturn(responseDTO);

            mockMvc.perform(post(BASE_URL + "/formation-101/cancel")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("formation-101"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        /**
         * Verifies that cancelling an enrollment as client is rejected with 403 Forbidden.
         */
        @Test
        @DisplayName("cancel_whenClientRole_shouldReturn403Forbidden")
        void cancel_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post(BASE_URL + "/formation-101/cancel")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Enrollment History & Course File Download")
    class HistoryAndDownloadEndpointTests {

        /**
         * Verifies that retrieving my-enrollments returns 200 OK with paginated list.
         */
        @Test
        @DisplayName("getMyEnrollments_whenArtisanRole_shouldReturn200Ok")
        void getMyEnrollments_whenArtisanRole_shouldReturn200Ok() throws Exception {
            FormationEnrollmentResponseDTO enrollmentDTO = FormationEnrollmentResponseDTO.builder()
                    .id("enrollment-101")
                    .status(EnrollmentStatus.CONFIRMED)
                    .build();

            FormationSummaryDTO formationDTO = FormationSummaryDTO.builder()
                    .id("formation-101")
                    .title("Traditional Leather Crafting")
                    .build();

            FormationEnrollmentDetailDTO detailDTO = FormationEnrollmentDetailDTO.builder()
                    .enrollment(enrollmentDTO)
                    .formation(formationDTO)
                    .build();

            when(formationEnrollmentService.getMyEnrollments(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(detailDTO)));

            mockMvc.perform(get(BASE_URL + "/my-enrollments")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].enrollment.id").value("enrollment-101"))
                    .andExpect(jsonPath("$.data.content[0].formation.id").value("formation-101"));
        }

        /**
         * Verifies that downloading a protected course file returns binary stream with headers.
         */
        @Test
        @DisplayName("downloadCourseFile_whenArtisanRole_shouldReturnBinaryStream")
        void downloadCourseFile_whenArtisanRole_shouldReturnBinaryStream() throws Exception {
            StorageResource resource = new StorageResource(
                    "formations/formation-101/syllabus.pdf",
                    new ByteArrayInputStream("sample file content".getBytes()),
                    "application/pdf",
                    19L,
                    "syllabus.pdf"
            );

            when(formationEnrollmentService.downloadCourseFile("formation-101", "file-101"))
                    .thenReturn(resource);

            mockMvc.perform(get(BASE_URL + "/formation-101/files/file-101/download")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("filename=\"syllabus.pdf\"")))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "19"));
        }

        /**
         * Verifies that downloading a course file as client is rejected with 403 Forbidden.
         */
        @Test
        @DisplayName("downloadCourseFile_whenClientRole_shouldReturn403Forbidden")
        void downloadCourseFile_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(BASE_URL + "/formation-101/files/file-101/download")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }
}
