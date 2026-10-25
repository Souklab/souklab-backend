package com.project.souklab.controller.artisan;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.service.artisan.ArtisanGalleryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for {@link ArtisanGalleryController}.
 * Verifies HTTP routing, multipart request consumption, ApiResponse envelope serialization,
 * and Spring Security role-based access control (@PreAuthorize("hasRole('ARTISAN')")).
 */
@ControllerSliceTest(controllers = ArtisanGalleryController.class)
class ArtisanGalleryControllerTest {

    private static final String GALLERY_URL = "/api/v1/artisan/gallery";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanGalleryService artisanGalleryService;

    @Nested
    @DisplayName("Upload Showcase Image (POST /api/v1/artisan/gallery)")
    class UploadImageEndpointTests {

        /**
         * Verifies that an authenticated artisan can upload a showcase image and receive 201 Created.
         */
        @Test
        @DisplayName("uploadImage_whenArtisanRole_shouldReturn201Created")
        void uploadImage_whenArtisanRole_shouldReturn201Created() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "pottery.jpg", MediaType.IMAGE_JPEG_VALUE, "image content".getBytes()
            );

            GalleryImageResponseDTO responseDTO = GalleryImageResponseDTO.builder()
                    .id("img-101")
                    .imageUrl("/api/v1/files/storage-key-101")
                    .title("Berber Tagine")
                    .caption("Clay tagine from Kabylie")
                    .displayOrder(0)
                    .build();

            when(artisanGalleryService.uploadImage(any(), eq("Berber Tagine"), eq("Clay tagine from Kabylie")))
                    .thenReturn(responseDTO);

            mockMvc.perform(multipart(GALLERY_URL)
                            .file(file)
                            .param("title", "Berber Tagine")
                            .param("caption", "Clay tagine from Kabylie")
                            .with(artisan()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(201))
                    .andExpect(jsonPath("$.message").value("Gallery image uploaded successfully"))
                    .andExpect(jsonPath("$.data.id").value("img-101"))
                    .andExpect(jsonPath("$.data.imageUrl").value("/api/v1/files/storage-key-101"))
                    .andExpect(jsonPath("$.data.title").value("Berber Tagine"))
                    .andExpect(jsonPath("$.data.caption").value("Clay tagine from Kabylie"))
                    .andExpect(jsonPath("$.data.displayOrder").value(0));

            verify(artisanGalleryService).uploadImage(any(), eq("Berber Tagine"), eq("Clay tagine from Kabylie"));
        }

        /**
         * Verifies that a client user receives 403 Forbidden.
         */
        @Test
        @DisplayName("uploadImage_whenClientRole_shouldReturn403Forbidden")
        void uploadImage_whenClientRole_shouldReturn403Forbidden() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "pottery.jpg", MediaType.IMAGE_JPEG_VALUE, "image content".getBytes()
            );

            mockMvc.perform(multipart(GALLERY_URL)
                            .file(file)
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("uploadImage_whenUnauthenticated_shouldReturn401Unauthorized")
        void uploadImage_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "pottery.jpg", MediaType.IMAGE_JPEG_VALUE, "image content".getBytes()
            );

            mockMvc.perform(multipart(GALLERY_URL)
                            .file(file))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Get My Gallery (GET /api/v1/artisan/gallery)")
    class GetMyGalleryEndpointTests {

        /**
         * Verifies that an authenticated artisan can retrieve their gallery images with 200 OK.
         */
        @Test
        @DisplayName("getMyGallery_whenArtisanRole_shouldReturn200Ok")
        void getMyGallery_whenArtisanRole_shouldReturn200Ok() throws Exception {
            GalleryImageResponseDTO img1 = GalleryImageResponseDTO.builder()
                    .id("img-1")
                    .imageUrl("/api/v1/files/key1")
                    .title("Title 1")
                    .displayOrder(0)
                    .build();

            when(artisanGalleryService.getMyGallery()).thenReturn(List.of(img1));

            mockMvc.perform(get(GALLERY_URL)
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Gallery retrieved successfully"))
                    .andExpect(jsonPath("$.data[0].id").value("img-1"))
                    .andExpect(jsonPath("$.data[0].displayOrder").value(0));

            verify(artisanGalleryService).getMyGallery();
        }

        /**
         * Verifies that a client user receives 403 Forbidden.
         */
        @Test
        @DisplayName("getMyGallery_whenClientRole_shouldReturn403Forbidden")
        void getMyGallery_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(GALLERY_URL)
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Reorder Gallery (PUT /api/v1/artisan/gallery/order)")
    class ReorderGalleryEndpointTests {

        /**
         * Verifies that an authenticated artisan can reorder their gallery images with 200 OK.
         */
        @Test
        @DisplayName("reorderGallery_whenArtisanRole_shouldReturn200Ok")
        void reorderGallery_whenArtisanRole_shouldReturn200Ok() throws Exception {
            String jsonBody = "[\"img-2\", \"img-1\"]";

            mockMvc.perform(put(GALLERY_URL + "/order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonBody)
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Gallery display order updated successfully"));

            verify(artisanGalleryService).reorderGallery(List.of("img-2", "img-1"));
        }

        /**
         * Verifies that a client user receives 403 Forbidden on reorder.
         */
        @Test
        @DisplayName("reorderGallery_whenClientRole_shouldReturn403Forbidden")
        void reorderGallery_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(put(GALLERY_URL + "/order")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"img-1\"]")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Delete Image (DELETE /api/v1/artisan/gallery/{id})")
    class DeleteImageEndpointTests {

        /**
         * Verifies that an authenticated artisan can delete a gallery image with 200 OK.
         */
        @Test
        @DisplayName("deleteImage_whenArtisanRole_shouldReturn200Ok")
        void deleteImage_whenArtisanRole_shouldReturn200Ok() throws Exception {
            mockMvc.perform(delete(GALLERY_URL + "/img-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Gallery image deleted successfully"));

            verify(artisanGalleryService).deleteImage("img-101");
        }

        /**
         * Verifies that a client user receives 403 Forbidden on deletion.
         */
        @Test
        @DisplayName("deleteImage_whenClientRole_shouldReturn403Forbidden")
        void deleteImage_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(delete(GALLERY_URL + "/img-101")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Get Single Gallery Image (GET /api/v1/artisan/gallery/{id})")
    class GetGalleryImageEndpointTests {

        @Test
        @DisplayName("getImage_whenArtisanRole_shouldReturn200Ok")
        void getImage_whenArtisanRole_shouldReturn200Ok() throws Exception {
            GalleryImageResponseDTO image = GalleryImageResponseDTO.builder()
                    .id("img-101")
                    .imageUrl("https://storage.souklab.com/img.jpg")
                    .displayOrder(1)
                    .title("Pottery")
                    .build();

            when(artisanGalleryService.getImage("img-101")).thenReturn(image);

            mockMvc.perform(get(GALLERY_URL + "/img-101")
                            .with(artisan()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("img-101"))
                    .andExpect(jsonPath("$.data.title").value("Pottery"));

            verify(artisanGalleryService).getImage("img-101");
        }

        @Test
        @DisplayName("getImage_whenClientRole_shouldReturn403Forbidden")
        void getImage_whenClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get(GALLERY_URL + "/img-101")
                            .with(client()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("getImage_whenUnauthenticated_shouldReturn401Unauthorized")
        void getImage_whenUnauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get(GALLERY_URL + "/img-101"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
