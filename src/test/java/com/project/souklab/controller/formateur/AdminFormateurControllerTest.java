package com.project.souklab.controller.formateur;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formateur.FormateurApproveDTO;
import com.project.souklab.dto.formateur.FormateurCooldownOverrideDTO;
import com.project.souklab.dto.formateur.FormateurGrantDTO;
import com.project.souklab.dto.formateur.FormateurRejectDTO;
import com.project.souklab.dto.formateur.FormateurRequestResponseDTO;
import com.project.souklab.dto.formateur.FormateurRevokeDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.service.formateur.ArtisanFormateurService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for AdminFormateurController.
 * Verifies class-level ROLE_ADMIN authorization, request validation,
 * service delegation, pagination parameters, and exception handling across all endpoints.
 * Explicit unauthenticated 401 tests verify the security filter chain rejects anonymous requests uniformly.
 */
@ControllerSliceTest(controllers = AdminFormateurController.class)
class AdminFormateurControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanFormateurService artisanFormateurService;

    @Nested
    @DisplayName("GET /api/v1/admin/formateur-requests")
    class GetPendingRequestsTests {

        /**
         * Verifies that an admin user can retrieve the list of pending formateur requests.
         */
        @Test
        @DisplayName("ROLE_ADMIN receives 200 OK with paginated list")
        void getPendingRequests_withAdminRole_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO item = FormateurRequestResponseDTO.builder()
                    .id("req-1")
                    .artisanId("artisan-1")
                    .artisanName("Ahmed Artisan")
                    .status(FormateurRequestStatus.PENDING)
                    .build();
            PaginatedResponse<FormateurRequestResponseDTO> pageResponse = PaginatedResponse.<FormateurRequestResponseDTO>builder()
                    .content(List.of(item))
                    .pageNumber(0)
                    .pageSize(10)
                    .totalElements(1L)
                    .totalPages(1)
                    .last(true)
                    .build();
            when(artisanFormateurService.getPendingRequests(any(Pageable.class))).thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/admin/formateur-requests")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("req-1"))
                    .andExpect(jsonPath("$.data.content[0].artisanName").value("Ahmed Artisan"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(artisanFormateurService).getPendingRequests(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
            assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        }

        /**
         * Verifies that custom pagination and sorting query parameters are bound correctly.
         */
        @Test
        @DisplayName("custom pagination query params are passed to service")
        void getPendingRequests_withCustomPagination_shouldPassPageableToService() throws Exception {
            PaginatedResponse<FormateurRequestResponseDTO> emptyPage = PaginatedResponse.<FormateurRequestResponseDTO>builder()
                    .content(List.of())
                    .pageNumber(2)
                    .pageSize(15)
                    .totalElements(0L)
                    .totalPages(0)
                    .last(true)
                    .build();
            when(artisanFormateurService.getPendingRequests(any(Pageable.class))).thenReturn(emptyPage);

            mockMvc.perform(get("/api/v1/admin/formateur-requests")
                            .with(admin())
                            .param("page", "2")
                            .param("size", "15")
                            .param("sort", "createdAt,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(2))
                    .andExpect(jsonPath("$.data.pageSize").value(15));

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(artisanFormateurService).getPendingRequests(captor.capture());
            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(captor.getValue().getPageSize()).isEqualTo(15);
        }

        /**
         * Verifies that non-admin users (ROLE_ARTISAN) are denied with 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void getPendingRequests_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/formateur-requests")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }

        /**
         * Verifies that unauthenticated requests receive 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getPendingRequests_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/formateur-requests"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/formateur-requests/{id}")
    class GetSingleRequestTests {

        @Test
        @DisplayName("admin can fetch single formateur request by ID")
        void getRequestById_withAdminRole_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO item = FormateurRequestResponseDTO.builder()
                    .id("req-1")
                    .artisanId("artisan-1")
                    .artisanName("Ahmed Artisan")
                    .status(FormateurRequestStatus.PENDING)
                    .build();

            when(artisanFormateurService.getRequestById("req-1")).thenReturn(item);

            mockMvc.perform(get("/api/v1/admin/formateur-requests/req-1")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("req-1"))
                    .andExpect(jsonPath("$.data.artisanName").value("Ahmed Artisan"));

            verify(artisanFormateurService).getRequestById("req-1");
        }

        @Test
        @DisplayName("non-admin receives 403 Forbidden")
        void getRequestById_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/formateur-requests/req-1")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/formateur-requests/{id}/approve")
    class ApproveRequestTests {

        /**
         * Verifies that an admin can approve a request with a valid DTO.
         */
        @Test
        @DisplayName("ROLE_ADMIN with valid DTO approves request and returns 200 OK")
        void approveRequest_withValidDto_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO response = FormateurRequestResponseDTO.builder()
                    .id("req-10")
                    .status(FormateurRequestStatus.APPROVED)
                    .adminNote("Verified diplomas and credentials")
                    .build();
            when(artisanFormateurService.approveRequest(eq("req-10"), any(FormateurApproveDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Verified diplomas and credentials\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Formateur request approved successfully."))
                    .andExpect(jsonPath("$.data.id").value("req-10"))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));

            ArgumentCaptor<FormateurApproveDTO> captor = ArgumentCaptor.forClass(FormateurApproveDTO.class);
            verify(artisanFormateurService).approveRequest(eq("req-10"), captor.capture());
            assertThat(captor.getValue().getAdminNote()).isEqualTo("Verified diplomas and credentials");
        }

        /**
         * Verifies that a blank admin note triggers a 422 Unprocessable Content validation error.
         */
        @Test
        @DisplayName("blank adminNote triggers 422 validation failure")
        void approveRequest_withBlankNote_shouldReturn422ValidationFailed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"   \"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.adminNote").exists());
        }

        /**
         * Verifies that missing adminNote field triggers 422 validation error.
         */
        @Test
        @DisplayName("missing adminNote field triggers 422 validation failure")
        void approveRequest_withEmptyObject_shouldReturn422ValidationFailed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors.adminNote").value("Admin note is required"));
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("request not found maps to 404 Not Found")
        void approveRequest_whenNotFound_shouldReturn404NotFound() throws Exception {
            when(artisanFormateurService.approveRequest(eq("req-999"), any(FormateurApproveDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Formateur request not found: req-999"));

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-999/approve")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("Formateur request not found: req-999"));
        }

        /**
         * Verifies that BadRequestException (e.g. request already processed) maps to 400 Bad Request.
         */
        @Test
        @DisplayName("non-pending request maps to 400 Bad Request")
        void approveRequest_whenNotPending_shouldReturn400BadRequest() throws Exception {
            when(artisanFormateurService.approveRequest(eq("req-10"), any(FormateurApproveDTO.class)))
                    .thenThrow(new BadRequestException("Request is not in PENDING status: APPROVED"));

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("Request is not in PENDING status: APPROVED"));
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void approveRequest_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void approveRequest_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-10/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/formateur-requests/{id}/reject")
    class RejectRequestTests {

        /**
         * Verifies that an admin can reject a request with an admin note and cooldown configuration.
         */
        @Test
        @DisplayName("ROLE_ADMIN with valid DTO rejects request and returns 200 OK")
        void rejectRequest_withValidDto_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO response = FormateurRequestResponseDTO.builder()
                    .id("req-20")
                    .status(FormateurRequestStatus.REJECTED)
                    .canReapply(true)
                    .cooldownUntil(LocalDateTime.of(2026, 10, 1, 0, 0))
                    .build();
            when(artisanFormateurService.rejectRequest(eq("req-20"), any(FormateurRejectDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-20/reject")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Insufficient workshop experience\",\"canReapply\":true,\"cooldownUntil\":\"2026-10-01T00:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Formateur request rejected successfully."))
                    .andExpect(jsonPath("$.data.id").value("req-20"))
                    .andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.canReapply").value(true));

            ArgumentCaptor<FormateurRejectDTO> captor = ArgumentCaptor.forClass(FormateurRejectDTO.class);
            verify(artisanFormateurService).rejectRequest(eq("req-20"), captor.capture());
            assertThat(captor.getValue().getAdminNote()).isEqualTo("Insufficient workshop experience");
            assertThat(captor.getValue().getCanReapply()).isTrue();
            assertThat(captor.getValue().getCooldownUntil()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        }

        /**
         * Verifies that blank adminNote triggers 422 validation failure.
         */
        @Test
        @DisplayName("blank adminNote triggers 422 validation failure")
        void rejectRequest_withBlankNote_shouldReturn422ValidationFailed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-20/reject")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.adminNote").value("Admin note is required"));
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("request not found maps to 404 Not Found")
        void rejectRequest_whenNotFound_shouldReturn404NotFound() throws Exception {
            when(artisanFormateurService.rejectRequest(eq("req-999"), any(FormateurRejectDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Formateur request not found: req-999"));

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-999/reject")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that BadRequestException maps to 400 Bad Request.
         */
        @Test
        @DisplayName("non-pending request maps to 400 Bad Request")
        void rejectRequest_whenNotPending_shouldReturn400BadRequest() throws Exception {
            when(artisanFormateurService.rejectRequest(eq("req-20"), any(FormateurRejectDTO.class)))
                    .thenThrow(new BadRequestException("Request is not in PENDING status: REJECTED"));

            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-20/reject")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        /**
         * Verifies that ROLE_ARTISAN receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void rejectRequest_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-20/reject")
                            .with(artisan())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void rejectRequest_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/req-20/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/artisans/{artisanId}/formateur-grant")
    class GrantDirectlyTests {

        /**
         * Verifies that an admin can grant formateur status directly to an artisan.
         */
        @Test
        @DisplayName("ROLE_ADMIN directly grants formateur status and returns 200 OK")
        void grantDirectly_withValidDto_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO response = FormateurRequestResponseDTO.builder()
                    .artisanId("artisan-50")
                    .status(FormateurRequestStatus.APPROVED)
                    .adminNote("Direct grant by committee")
                    .build();
            when(artisanFormateurService.grantDirectly(eq("artisan-50"), any(FormateurGrantDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/artisans/artisan-50/formateur-grant")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Direct grant by committee\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Formateur status granted successfully."))
                    .andExpect(jsonPath("$.data.artisanId").value("artisan-50"));

            ArgumentCaptor<FormateurGrantDTO> captor = ArgumentCaptor.forClass(FormateurGrantDTO.class);
            verify(artisanFormateurService).grantDirectly(eq("artisan-50"), captor.capture());
            assertThat(captor.getValue().getAdminNote()).isEqualTo("Direct grant by committee");
        }

        /**
         * Verifies that a blank note triggers 422 validation failure.
         */
        @Test
        @DisplayName("blank adminNote triggers 422 validation failure")
        void grantDirectly_withBlankNote_shouldReturn422ValidationFailed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-50/formateur-grant")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.adminNote").value("Admin note is required"));
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("artisan not found maps to 404 Not Found")
        void grantDirectly_whenArtisanNotFound_shouldReturn404NotFound() throws Exception {
            when(artisanFormateurService.grantDirectly(eq("artisan-999"), any(FormateurGrantDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Artisan not found with ID: artisan-999"));

            mockMvc.perform(post("/api/v1/admin/artisans/artisan-999/formateur-grant")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that ConflictException when artisan is already a teacher maps to 409 Conflict.
         */
        @Test
        @DisplayName("artisan already formateur maps to 409 Conflict")
        void grantDirectly_whenAlreadyFormateur_shouldReturn409Conflict() throws Exception {
            when(artisanFormateurService.grantDirectly(eq("artisan-50"), any(FormateurGrantDTO.class)))
                    .thenThrow(new ConflictException("Artisan is already a formateur"));

            mockMvc.perform(post("/api/v1/admin/artisans/artisan-50/formateur-grant")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409));
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void grantDirectly_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-50/formateur-grant")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void grantDirectly_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-50/formateur-grant")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminNote\":\"Valid note\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/artisans/{artisanId}/formateur-revoke")
    class RevokeDirectlyTests {

        /**
         * Verifies that an admin can revoke formateur status directly from an artisan.
         */
        @Test
        @DisplayName("ROLE_ADMIN directly revokes formateur status and returns 200 OK")
        void revokeDirectly_withValidDto_shouldReturn200Ok() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-60/formateur-revoke")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Inactivity over 12 months\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Formateur status revoked successfully."))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            ArgumentCaptor<FormateurRevokeDTO> captor = ArgumentCaptor.forClass(FormateurRevokeDTO.class);
            verify(artisanFormateurService).revokeDirectly(eq("artisan-60"), captor.capture());
            assertThat(captor.getValue().getReason()).isEqualTo("Inactivity over 12 months");
        }

        /**
         * Verifies that blank reason triggers 422 validation failure.
         */
        @Test
        @DisplayName("blank reason triggers 422 validation failure")
        void revokeDirectly_withBlankReason_shouldReturn422ValidationFailed() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-60/formateur-revoke")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"   \"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.reason").value("Reason is required"));
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("artisan not found maps to 404 Not Found")
        void revokeDirectly_whenArtisanNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("Artisan not found with ID: artisan-999"))
                    .when(artisanFormateurService).revokeDirectly(eq("artisan-999"), any(FormateurRevokeDTO.class));

            mockMvc.perform(post("/api/v1/admin/artisans/artisan-999/formateur-revoke")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Valid reason\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that BadRequestException (not currently a formateur) maps to 400 Bad Request.
         */
        @Test
        @DisplayName("artisan not a formateur maps to 400 Bad Request")
        void revokeDirectly_whenNotFormateur_shouldReturn400BadRequest() throws Exception {
            doThrow(new BadRequestException("Artisan is not currently a formateur"))
                    .when(artisanFormateurService).revokeDirectly(eq("artisan-60"), any(FormateurRevokeDTO.class));

            mockMvc.perform(post("/api/v1/admin/artisans/artisan-60/formateur-revoke")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Valid reason\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void revokeDirectly_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-60/formateur-revoke")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Valid reason\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void revokeDirectly_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/artisans/artisan-60/formateur-revoke")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Valid reason\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/formateur-requests/{artisanId}/lift-cooldown")
    class LiftCooldownTests {

        /**
         * Verifies that an admin can update cooldown configuration with an explicit body.
         */
        @Test
        @DisplayName("ROLE_ADMIN with explicit body updates cooldown and returns 200 OK")
        void liftCooldown_withExplicitBody_shouldReturn200Ok() throws Exception {
            FormateurRequestResponseDTO response = FormateurRequestResponseDTO.builder()
                    .artisanId("artisan-70")
                    .canReapply(true)
                    .cooldownUntil(null)
                    .build();
            when(artisanFormateurService.liftCooldown(eq("artisan-70"), any(FormateurCooldownOverrideDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/formateur-requests/artisan-70/lift-cooldown")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"canReapply\":true,\"cooldownUntil\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Cooldown configuration updated successfully."))
                    .andExpect(jsonPath("$.data.artisanId").value("artisan-70"))
                    .andExpect(jsonPath("$.data.canReapply").value(true));

            ArgumentCaptor<FormateurCooldownOverrideDTO> captor = ArgumentCaptor.forClass(FormateurCooldownOverrideDTO.class);
            verify(artisanFormateurService).liftCooldown(eq("artisan-70"), captor.capture());
            assertThat(captor.getValue().getCanReapply()).isTrue();
            assertThat(captor.getValue().getCooldownUntil()).isNull();
        }

        /**
         * Verifies that liftCooldown tolerates an omitted/null request body and falls back
         * to new FormateurCooldownOverrideDTO(), successfully returning 200 OK.
         */
        @Test
        @DisplayName("ROLE_ADMIN with omitted body falls back to default DTO and returns 200 OK")
        void liftCooldown_withOmittedBody_shouldFallbackToDefaultDtoAndReturn200Ok() throws Exception {
            FormateurRequestResponseDTO response = FormateurRequestResponseDTO.builder()
                    .artisanId("artisan-70")
                    .build();
            when(artisanFormateurService.liftCooldown(eq("artisan-70"), any(FormateurCooldownOverrideDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/formateur-requests/artisan-70/lift-cooldown")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.artisanId").value("artisan-70"));

            ArgumentCaptor<FormateurCooldownOverrideDTO> captor = ArgumentCaptor.forClass(FormateurCooldownOverrideDTO.class);
            verify(artisanFormateurService).liftCooldown(eq("artisan-70"), captor.capture());
            assertThat(captor.getValue().getCanReapply()).isNull();
            assertThat(captor.getValue().getCooldownUntil()).isNull();
            assertThat(captor.getValue()).isEqualTo(new FormateurCooldownOverrideDTO());
        }

        /**
         * Verifies that ResourceNotFoundException when no formateur record exists maps to 404 Not Found.
         */
        @Test
        @DisplayName("no formateur record found maps to 404 Not Found")
        void liftCooldown_whenNotFound_shouldReturn404NotFound() throws Exception {
            when(artisanFormateurService.liftCooldown(eq("artisan-999"), any(FormateurCooldownOverrideDTO.class)))
                    .thenThrow(new ResourceNotFoundException("No Formateur request record found for artisan ID: artisan-999"));

            mockMvc.perform(post("/api/v1/admin/formateur-requests/artisan-999/lift-cooldown")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("No Formateur request record found for artisan ID: artisan-999"));
        }

        /**
         * Verifies that ROLE_ARTISAN receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void liftCooldown_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/artisan-70/lift-cooldown")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void liftCooldown_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/formateur-requests/artisan-70/lift-cooldown")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }
}
