package com.project.souklab.controller.artisan;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.service.artisan.ArtisanCertificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link ArtisanCertificationController}.
 * Verifies HTTP routing, multipart request consumption, date formatting binding,
 * ApiResponse envelope serialization, and Spring Security role-based access control.
 */
@ControllerSliceTest(controllers = ArtisanCertificationController.class)
class ArtisanCertificationControllerTest {

    private static final String CERTIFICATIONS_URL = "/api/v1/artisan/certifications";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanCertificationService artisanCertificationService;

    @Nested
    @DisplayName("Upload Certification (POST /api/v1/artisan/certifications)")
    class UploadCertificationEndpointTests {

        /**
         * Verifies that an authenticated artisan can upload a certification document and receive 201 Created.
         */
        @Test
        @DisplayName("uploadCertification_whenArtisanRole_shouldReturn201Created")
        void uploadCertification_whenArtisanRole_shouldReturn201Created() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "cert.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
            );

            LocalDate issued = LocalDate.of(2025, 1, 15);
            LocalDate expires = LocalDate.of(2030, 1, 15);

            CertificationResponseDTO responseDTO = CertificationResponseDTO.builder()
                    .id("cert-101")
                    .title("Carte Artisan CAM")
                    .issuer("CAM Alger")
                    .issuedAt(issued)
                    .expiresAt(expires)
                    .isVerified(false)
                    .documentUrl("/api/v1/files/cert-key-101")
                    .build();

            when(artisanCertificationService.uploadCertification(
                    any(), eq("Carte Artisan CAM"), eq("CAM Alger"), eq(issued), eq(expires)
            )).thenReturn(responseDTO);

            mockMvc.perform(multipart(CERTIFICATIONS_URL)
                            .file(file)
                            .param("title", "Carte Artisan CAM")
                            .param("issuer", "CAM Alger")
                            .param("issuedAt", "2025-01-15")
                            .param("expiresAt", "2030-01-15")
                            .with(artisan()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.message").value("Certification uploaded successfully"))
                    .andExpect(jsonPath("$.data.id").value("cert-101"))
                    .andExpect(jsonPath("$.data.title").value("Carte Artisan CAM"))
                    .andExpect(jsonPath("$.data.issuer").value("CAM Alger"))
                    .andExpect(jsonPath("$.data.issuedAt").value("2025-01-15"))
                    .andExpect(jsonPath("$.data.expiresAt").value("2030-01-15"))
                    .andExpect(jsonPath("$.data.verified").value(false))
                    .andExpect(jsonPath("$.data.documentUrl").value("/api/v1/files/cert-key-101"));

            verify(artisanCertificationService).uploadCertification(
                    any(), eq("Carte Artisan CAM"), eq("CAM Alger"), eq(issued), eq(expires)
            );
        }

        /**
         * Verifies that a client user receives 403 Forbidden.
         */
        @Test
        @DisplayName("uploadCertification_whenClientRole_shouldReturn403Forbidden")
        void uploadCertification_whenClientRole_shouldReturn403Forbidden() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "cert.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
            );

            mockMvc.perform(multipart(CERTIFICATIONS_URL)
                            .file(file)
                            .param("title", "Carte")
                            .param("issuer", "CAM")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("uploadCertification_whenUnauthenticated_shouldReturn401Unauthorized")
        void uploadCertification_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "cert.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf content".getBytes()
            );

            mockMvc.perform(multipart(CERTIFICATIONS_URL)
                            .file(file)
                            .param("title", "Carte")
                            .param("issuer", "CAM"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Get My Certifications (GET /api/v1/artisan/certifications)")
    class GetMyCertificationsEndpointTests {

        /**
         * Verifies that an authenticated artisan can retrieve their certifications list with 200 OK.
         */
        @Test
        @DisplayName("getMyCertifications_whenArtisanRole_shouldReturn200Ok")
        void getMyCertifications_whenArtisanRole_shouldReturn200Ok() throws Exception {
            CertificationResponseDTO cert1 = CertificationResponseDTO.builder()
                    .id("cert-1")
                    .title("Diplome")
                    .issuer("CAM")
                    .isVerified(true)
                    .build();

            when(artisanCertificationService.getMyCertifications()).thenReturn(List.of(cert1));

            mockMvc.perform(get(CERTIFICATIONS_URL)
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Certifications retrieved successfully"))
                    .andExpect(jsonPath("$.data[0].id").value("cert-1"))
                    .andExpect(jsonPath("$.data[0].verified").value(true));

            verify(artisanCertificationService).getMyCertifications();
        }

        /**
         * Verifies that a client user receives 403 Forbidden.
         */
        @Test
        @DisplayName("getMyCertifications_whenClientRole_shouldReturn403Forbidden")
        void getMyCertifications_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(CERTIFICATIONS_URL)
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Delete Certification (DELETE /api/v1/artisan/certifications/{id})")
    class DeleteCertificationEndpointTests {

        /**
         * Verifies that an authenticated artisan can delete a certification with 200 OK.
         */
        @Test
        @DisplayName("deleteCertification_whenArtisanRole_shouldReturn200Ok")
        void deleteCertification_whenArtisanRole_shouldReturn200Ok() throws Exception {
            mockMvc.perform(delete(CERTIFICATIONS_URL + "/cert-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Certification deleted successfully"));

            verify(artisanCertificationService).deleteCertification("cert-101");
        }

        /**
         * Verifies that a client user receives 403 Forbidden on deletion.
         */
        @Test
        @DisplayName("deleteCertification_whenClientRole_shouldReturn403Forbidden")
        void deleteCertification_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(delete(CERTIFICATIONS_URL + "/cert-101")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Get Single Certification (GET /api/v1/artisan/certifications/{id})")
    class GetCertificationEndpointTests {

        @Test
        @DisplayName("getCertification_whenArtisanRole_shouldReturn200Ok")
        void getCertification_whenArtisanRole_shouldReturn200Ok() throws Exception {
            CertificationResponseDTO cert = CertificationResponseDTO.builder()
                    .id("cert-101")
                    .title("Diplome")
                    .issuer("CAM")
                    .isVerified(true)
                    .build();

            when(artisanCertificationService.getCertification("cert-101")).thenReturn(cert);

            mockMvc.perform(get(CERTIFICATIONS_URL + "/cert-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("cert-101"))
                    .andExpect(jsonPath("$.data.title").value("Diplome"));

            verify(artisanCertificationService).getCertification("cert-101");
        }

        @Test
        @DisplayName("getCertification_whenClientRole_shouldReturn403Forbidden")
        void getCertification_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(CERTIFICATIONS_URL + "/cert-101")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("getCertification_whenUnauthenticated_shouldReturn401Unauthorized")
        void getCertification_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get(CERTIFICATIONS_URL + "/cert-101"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
