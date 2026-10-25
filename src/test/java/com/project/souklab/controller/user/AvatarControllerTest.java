package com.project.souklab.controller.user;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.user.AvatarResponseDTO;
import com.project.souklab.exception.AvatarLimitExceededException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.exception.UnsupportedFileTypeException;
import com.project.souklab.filestorage.exception.VirusDetectedException;
import com.project.souklab.filestorage.exception.FileTooLargeException;
import com.project.souklab.model.User;
import com.project.souklab.service.user.AvatarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice test for {@link AvatarController}.
 * Verifies routing, authentication enforcement, input validation, quota error mapping,
 * and HTTP response envelopes for POST /api/v1/users/me/avatars.
 */
@ControllerSliceTest(controllers = AvatarController.class)
class AvatarControllerTest {

    private static final String AVATAR_URL = "/api/v1/users/me/avatars";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvatarService avatarService;

    private User testUser;
    private MockMultipartFile validFile;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("client@souklab.com")
                .firstName("Amina")
                .lastName("K")
                .build();
        testUser.setId("user-client-123");

        validFile = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                new byte[]{1, 2, 3, 4}
        );

    }

    @Nested
    @DisplayName("Authentication & Authorization")
    class SecurityTests {

        /**
         * Verifies unauthenticated calls receive 401 Unauthorized.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars without authentication returns 401 Unauthorized")
        void uploadAvatar_whenUnauthenticated_returns401() throws Exception {
            mockMvc.perform(multipart(AVATAR_URL)
                            .file(validFile))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));

            verifyNoInteractions(avatarService);
        }

        @Test
        @DisplayName("GET /api/v1/users/me/avatars without authentication returns 401 Unauthorized")
        void listAvatars_whenUnauthenticated_returns401() throws Exception {
            mockMvc.perform(get(AVATAR_URL))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));

            verifyNoInteractions(avatarService);
        }

        @Test
        @DisplayName("DELETE /api/v1/users/me/avatars/{id} without authentication returns 401 Unauthorized")
        void deleteAvatar_whenUnauthenticated_returns401() throws Exception {
            mockMvc.perform(delete(AVATAR_URL + "/any-id"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));

            verifyNoInteractions(avatarService);
        }

        @Test
        @DisplayName("PUT /api/v1/users/me/avatars/{id}/activate without authentication returns 401 Unauthorized")
        void activateAvatar_whenUnauthenticated_returns401() throws Exception {
            mockMvc.perform(put(AVATAR_URL + "/any-id/activate"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));

            verifyNoInteractions(avatarService);
        }
    }

    @Nested
    @DisplayName("Upload Success & Pipeline Mapping")
    class SuccessTests {

        /**
         * Verifies 201 Created response envelope with AvatarResponseDTO containing URLs for all 3 tiers.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars with valid file returns 201 Created and tier URLs")
        void uploadAvatar_whenValidFile_returns201Created() throws Exception {
            AvatarResponseDTO responseDTO = AvatarResponseDTO.builder()
                    .id("avatar-uuid-1")
                    .urlOriginal("/api/v1/files/orig-key.png")
                    .urlMedium("/api/v1/files/med-key.png")
                    .urlThumbnail("/api/v1/files/thumb-key.png")
                    .originalFilename("avatar.png")
                    .contentType("image/png")
                    .fileSize(4L)
                    .isActive(true)
                    .uploadedAt(LocalDateTime.now())
                    .build();

            when(avatarService.uploadAvatar(any())).thenReturn(responseDTO);

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(validFile)
                            .with(client()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("avatar-uuid-1"))
                    .andExpect(jsonPath("$.data.urlOriginal").value("/api/v1/files/orig-key.png"))
                    .andExpect(jsonPath("$.data.urlMedium").value("/api/v1/files/med-key.png"))
                    .andExpect(jsonPath("$.data.urlThumbnail").value("/api/v1/files/thumb-key.png"))
                    .andExpect(jsonPath("$.data.isActive").value(true));
        }
    }

    @Nested
    @DisplayName("Validation & Error Envelopes")
    class ValidationTests {

        /**
         * Verifies 400 Bad Request when the file parameter is empty.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars with empty file returns 400 Bad Request")
        void uploadAvatar_whenEmptyFile_returns400() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
            when(avatarService.uploadAvatar(any()))
                    .thenThrow(new BadRequestException("Avatar file is required and cannot be empty"));

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(emptyFile)
                            .with(client()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            verify(avatarService).uploadAvatar(any());
        }

        /**
         * Verifies 400 Bad Request when file size exceeds configured avatar limit.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars with oversized file returns 400 Bad Request with FILE_TOO_LARGE")
        void uploadAvatar_whenOversizedFile_returns400() throws Exception {
            MockMultipartFile oversized = new MockMultipartFile(
                    "file",
                    "huge.jpg",
                    "image/jpeg",
                    new byte[1024 * 1024 * 11]
            );
            when(avatarService.uploadAvatar(any()))
                    .thenThrow(new FileTooLargeException(oversized.getSize(), 10L * 1024 * 1024));

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(oversized)
                            .with(client()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));

            verify(avatarService).uploadAvatar(any());
        }

        /**
         * Verifies 400 Bad Request when an unsupported file type is uploaded.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars with unsupported file type returns 400 Bad Request")
        void uploadAvatar_whenUnsupportedFileType_returns400() throws Exception {
            when(avatarService.uploadAvatar(any()))
                    .thenThrow(new UnsupportedFileTypeException("application/pdf"));

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(validFile)
                            .with(client()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_FILE_TYPE"));
        }

        /**
         * Verifies 422 Unprocessable Content when malware is detected.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars when virus detected returns 422 Unprocessable Content")
        void uploadAvatar_whenVirusDetected_returns422() throws Exception {
            when(avatarService.uploadAvatar(any()))
                    .thenThrow(new VirusDetectedException("Eicar-Signature"));

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(validFile)
                            .with(client()))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("VIRUS_DETECTED"));
        }

        /**
         * Verifies 409 Conflict when the user has already reached their 10-avatar gallery limit.
         */
        @Test
        @DisplayName("POST /api/v1/users/me/avatars when quota exceeded returns 409 Conflict")
        void uploadAvatar_whenQuotaExceeded_returns409() throws Exception {
            when(avatarService.uploadAvatar(any()))
                    .thenThrow(new AvatarLimitExceededException("Maximum avatar limit of 10 reached"));

            mockMvc.perform(multipart(AVATAR_URL)
                            .file(validFile)
                            .with(client()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("AVATAR_LIMIT_EXCEEDED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/users/me/avatars")
    class ListAvatarsEndpointTests {

        @Test
        @DisplayName("authenticated user retrieves avatar gallery returning 200 OK with PaginatedResponse")
        void listAvatars_whenAuthenticated_returns200WithPaginatedEnvelope() throws Exception {
            AvatarResponseDTO dto = AvatarResponseDTO.builder()
                    .id("av-1")
                    .urlOriginal("/api/v1/files/orig-1.png")
                    .urlMedium("/api/v1/files/med-1.png")
                    .urlThumbnail("/api/v1/files/thumb-1.png")
                    .originalFilename("avatar.png")
                    .contentType("image/png")
                    .fileSize(1024L)
                    .isActive(true)
                    .uploadedAt(LocalDateTime.now())
                    .build();

            PaginatedResponse<AvatarResponseDTO> paginated = PaginatedResponse.<AvatarResponseDTO>builder()
                    .content(List.of(dto))
                    .pageNumber(0)
                    .pageSize(20)
                    .totalElements(1L)
                    .totalPages(1)
                    .last(true)
                    .build();

            when(avatarService.listAvatars(any(Pageable.class))).thenReturn(paginated);

            mockMvc.perform(get(AVATAR_URL)
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("av-1"))
                    .andExpect(jsonPath("$.data.content[0].urlThumbnail").value("/api/v1/files/thumb-1.png"))
                    .andExpect(jsonPath("$.data.content[0].isActive").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(20))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.last").value(true));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/users/me/avatars/{id}")
    class DeleteAvatarEndpointTests {

        @Test
        @DisplayName("authenticated user deletes avatar returning 200 OK with null data")
        void deleteAvatar_whenAuthenticated_returns200WithNullData() throws Exception {
            doNothing().when(avatarService).deleteAvatar(eq("av-1"));

            mockMvc.perform(delete(AVATAR_URL + "/av-1")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Avatar deleted successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(avatarService).deleteAvatar(eq("av-1"));
        }

        @Test
        @DisplayName("delete avatar with bad or unowned ID returns 404 Not Found")
        void deleteAvatar_whenNotFoundOrNotOwned_returns404() throws Exception {
            doThrow(new ResourceNotFoundException("Avatar not found with id: missing-id"))
                    .when(avatarService).deleteAvatar(eq("missing-id"));

            mockMvc.perform(delete(AVATAR_URL + "/missing-id")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/users/me/avatars/{id}/activate")
    class ActivateAvatarEndpointTests {

        @Test
        @DisplayName("authenticated user activates avatar returning 200 OK with AvatarResponseDTO")
        void activateAvatar_whenAuthenticated_returns200WithUpdatedDTO() throws Exception {
            AvatarResponseDTO responseDTO = AvatarResponseDTO.builder()
                    .id("av-target")
                    .urlOriginal("/api/v1/files/orig-target.png")
                    .urlMedium("/api/v1/files/med-target.png")
                    .urlThumbnail("/api/v1/files/thumb-target.png")
                    .originalFilename("target.png")
                    .contentType("image/png")
                    .fileSize(2048L)
                    .isActive(true)
                    .uploadedAt(LocalDateTime.now())
                    .build();

            when(avatarService.activateAvatar(eq("av-target"))).thenReturn(responseDTO);

            mockMvc.perform(put(AVATAR_URL + "/av-target/activate")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Avatar activated successfully"))
                    .andExpect(jsonPath("$.data.id").value("av-target"))
                    .andExpect(jsonPath("$.data.isActive").value(true));
        }

        @Test
        @DisplayName("activating already active avatar returns 200 OK no-op with current AvatarResponseDTO")
        void activateAvatar_whenAlreadyActive_returns200NoOp() throws Exception {
            AvatarResponseDTO responseDTO = AvatarResponseDTO.builder()
                    .id("av-already-active")
                    .urlOriginal("/api/v1/files/orig-already.png")
                    .urlMedium("/api/v1/files/med-already.png")
                    .urlThumbnail("/api/v1/files/thumb-already.png")
                    .originalFilename("already.png")
                    .contentType("image/png")
                    .fileSize(2048L)
                    .isActive(true)
                    .uploadedAt(LocalDateTime.now())
                    .build();

            when(avatarService.activateAvatar(eq("av-already-active"))).thenReturn(responseDTO);

            mockMvc.perform(put(AVATAR_URL + "/av-already-active/activate")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("av-already-active"))
                    .andExpect(jsonPath("$.data.isActive").value(true));
        }

        @Test
        @DisplayName("activating avatar with bad or unowned ID returns 404 Not Found")
        void activateAvatar_whenNotFoundOrNotOwned_returns404() throws Exception {
            when(avatarService.activateAvatar(eq("bad-id")))
                    .thenThrow(new ResourceNotFoundException("Avatar not found with id: bad-id"));

            mockMvc.perform(put(AVATAR_URL + "/bad-id/activate")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        }
    }
}
