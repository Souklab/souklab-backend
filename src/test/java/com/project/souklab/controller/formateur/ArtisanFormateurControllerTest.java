package com.project.souklab.controller.formateur;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.formateur.FormateurRequestDTO;
import com.project.souklab.dto.formateur.FormateurRequestResponseDTO;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.service.formateur.ArtisanFormateurService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for ArtisanFormateurController.
 * Verifies security authorization, fallback DTO instantiation on null bodies,
 * ApiResponse envelope formatting, and service exception mapping.
 */
@ControllerSliceTest(controllers = ArtisanFormateurController.class)
class ArtisanFormateurControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanFormateurService artisanFormateurService;

    /**
     * Verifies that an artisan can submit a formateur request with a valid body
     * and receives 201 Created with the standard ApiResponse envelope.
     */
    @Test
    @DisplayName("submitRequest: ROLE_ARTISAN with body returns 201 Created and invokes service")
    void submitRequest_withArtisanRoleAndBody_shouldReturn201Created() throws Exception {
        FormateurRequestResponseDTO mockResponse = FormateurRequestResponseDTO.builder()
                .id("req-101")
                .artisanId("artisan-101")
                .artisanName("Karim Artisan")
                .status(FormateurRequestStatus.PENDING)
                .canReapply(true)
                .createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan("karim@souklab.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivation\":\"I have 10 years experience in ceramic craft.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.message").value("Formateur request submitted successfully."))
                .andExpect(jsonPath("$.data.id").value("req-101"))
                .andExpect(jsonPath("$.data.artisanId").value("artisan-101"))
                .andExpect(jsonPath("$.data.artisanName").value("Karim Artisan"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.canReapply").value(true));

        ArgumentCaptor<FormateurRequestDTO> captor = ArgumentCaptor.forClass(FormateurRequestDTO.class);
        verify(artisanFormateurService).submitRequest(captor.capture());
        assertThat(captor.getValue().getMotivation()).isEqualTo("I have 10 years experience in ceramic craft.");
    }

    /**
     * Verifies that submitRequest tolerates an omitted request body and falls back
     * to a new default-constructed FormateurRequestDTO, returning 201 Created.
     */
    @Test
    @DisplayName("submitRequest: ROLE_ARTISAN with omitted body falls back to default DTO")
    void submitRequest_withOmittedBody_shouldFallbackToDefaultDtoAndReturn201Created() throws Exception {
        FormateurRequestResponseDTO mockResponse = FormateurRequestResponseDTO.builder()
                .id("req-102")
                .artisanId("artisan-102")
                .status(FormateurRequestStatus.PENDING)
                .build();
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value("req-102"));

        ArgumentCaptor<FormateurRequestDTO> captor = ArgumentCaptor.forClass(FormateurRequestDTO.class);
        verify(artisanFormateurService).submitRequest(captor.capture());
        assertThat(captor.getValue().getMotivation()).isNull();
        assertThat(captor.getValue()).isEqualTo(new FormateurRequestDTO());
    }

    /**
     * Verifies that the typed permission post-processor authorizes the request.
     */
    @Test
    @DisplayName("submitRequest: typed permission authority returns 201 Created")
    void submitRequest_withTypedPermission_shouldReturn201Created() throws Exception {
        FormateurRequestResponseDTO mockResponse = FormateurRequestResponseDTO.builder()
                .id("req-103")
                .build();
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class))).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andExpect(jsonPath("$.data.id").value("req-103"));
    }

    /**
     * Verifies that a client user (ROLE_CLIENT) is denied access with 403 Forbidden
     * and receives the standard ApiResponse error envelope with errorCode = FORBIDDEN.
     */
    @Test
    @DisplayName("submitRequest: ROLE_CLIENT returns 403 Forbidden with proper ApiResponse envelope")
    void submitRequest_withClientRole_shouldReturn403Forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(client())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."));
    }

    /**
     * Verifies that an unauthenticated request returns 401 Unauthorized
     * with the standard ApiResponse envelope from the test authentication entry point.
     */
    @Test
    @DisplayName("submitRequest: unauthenticated request returns 401 Unauthorized")
    void submitRequest_unauthenticated_shouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    /**
     * Verifies that ConflictException for a duplicate pending request maps to 409 Conflict.
     */
    @Test
    @DisplayName("submitRequest: duplicate pending request maps to 409 Conflict")
    void submitRequest_whenDuplicatePending_shouldReturn409Conflict() throws Exception {
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class)))
                .thenThrow(new ConflictException("A pending formateur request already exists for this artisan."));

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("A pending formateur request already exists for this artisan."));
    }

    /**
     * Verifies that ConflictException when artisan is already a teacher maps to 409 Conflict.
     */
    @Test
    @DisplayName("submitRequest: already a formateur maps to 409 Conflict")
    void submitRequest_whenAlreadyFormateur_shouldReturn409Conflict() throws Exception {
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class)))
                .thenThrow(new ConflictException("Artisan is already a formateur"));

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Artisan is already a formateur"));
    }

    /**
     * Verifies that ForbiddenException for active cooldown maps to 403 Forbidden.
     */
    @Test
    @DisplayName("submitRequest: active cooldown maps to 403 Forbidden")
    void submitRequest_whenCooldownActive_shouldReturn403Forbidden() throws Exception {
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class)))
                .thenThrow(new ForbiddenException("You cannot submit a request at this time. Cooldown active until 2026-10-01T00:00:00"));

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("You cannot submit a request at this time. Cooldown active until 2026-10-01T00:00:00"));
    }

    /**
     * Verifies that ForbiddenException for permanent reapplication block maps to 403 Forbidden.
     */
    @Test
    @DisplayName("submitRequest: permanent block maps to 403 Forbidden")
    void submitRequest_whenPermanentlyBlocked_shouldReturn403Forbidden() throws Exception {
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class)))
                .thenThrow(new ForbiddenException("You are permanently ineligible to re-apply for formateur status."));

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("You are permanently ineligible to re-apply for formateur status."));
    }

    /**
     * Verifies that ResourceNotFoundException when artisan profile is missing maps to 404 Not Found.
     */
    @Test
    @DisplayName("submitRequest: missing artisan profile maps to 404 Not Found")
    void submitRequest_whenArtisanProfileNotFound_shouldReturn404NotFound() throws Exception {
        when(artisanFormateurService.submitRequest(any(FormateurRequestDTO.class)))
                .thenThrow(new ResourceNotFoundException("Artisan profile not found for user: karim@souklab.com"));

        mockMvc.perform(post("/api/v1/artisan/formateur-request")
                        .with(artisan("karim@souklab.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Artisan profile not found for user: karim@souklab.com"));
    }
}
