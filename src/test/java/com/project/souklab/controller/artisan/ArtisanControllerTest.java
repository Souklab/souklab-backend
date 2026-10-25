package com.project.souklab.controller.artisan;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.dto.catalog.EpoqueSummaryDTO;
import com.project.souklab.dto.catalog.JobSubCategorySummaryDTO;
import com.project.souklab.dto.catalog.MaterialSummaryDTO;
import com.project.souklab.dto.catalog.RegionSummaryDTO;
import com.project.souklab.dto.catalog.TechniqueSummaryDTO;
import com.project.souklab.dto.profile.ArtisanPublicViewDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.service.artisan.ArtisanProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for ArtisanController.
 * Verifies endpoint routing, path-variable binding, ApiResponse encapsulation,
 * and exception mapping for artisan public profile retrieval.
 *
 * Note on viewer identity and masking:
 * The controller does not resolve viewer identity or pass a principal to ArtisanProfileService;
 * the service resolves the current user directly from SecurityUtils.getCurrentUsername() to handle
 * contact info masking (contactInfoLocked) and deduplicated view count tracking.
 * Those domain rules are verified in service-level tests; this controller slice verifies
 * HTTP boundary routing, security filter chain enforcement, and response encapsulation.
 */
@ControllerSliceTest(controllers = ArtisanController.class)
class ArtisanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtisanProfileService artisanProfileService;

    @Nested
    @DisplayName("GET /api/v1/artisan/{id}")
    class GetArtisanProfileTests {

        /**
         * Verifies that an authenticated viewer can retrieve an unmasked artisan profile by ID.
         */
        @Test
        @DisplayName("authenticated user retrieves unmasked artisan profile returning 200 OK")
        void getArtisanProfile_whenAuthenticated_shouldReturn200Ok() throws Exception {
            ArtisanPublicViewDTO profile = ArtisanPublicViewDTO.builder()
                    .id("artisan-1")
                    .bio("Handmade pottery specialist")
                    .city("Algiers")
                    .regionId("reg-16")
                    .region(RegionSummaryDTO.builder()
                            .id("reg-16")
                            .name("Alger")
                            .slug("alger")
                            .code("16")
                            .build())
                    .subCategoryId("sub-4")
                    .subCategory(JobSubCategorySummaryDTO.builder()
                            .id("sub-4")
                            .name("Poterie")
                            .slug("poterie")
                            .categoryId("cat-1")
                            .categoryName("Artisanat Traditionnel")
                            .build())
                    .materials(Set.of(MaterialSummaryDTO.builder().id("mat-1").name("Argile").slug("argile").build()))
                    .techniques(Set.of(TechniqueSummaryDTO.builder().id("tech-1").name("Modelage").slug("modelage").build()))
                    .epoques(Set.of(EpoqueSummaryDTO.builder().id("epo-1").name("Contemporain").slug("contemporain").build()))
                    .galleryImages(List.of(
                            GalleryImageResponseDTO.builder()
                                    .id("gal-1")
                                    .imageUrl("https://storage.souklab.dz/gal1.jpg")
                                    .title("Vase")
                                    .caption("Argile cuite")
                                    .displayOrder(0)
                                    .build(),
                            GalleryImageResponseDTO.builder()
                                    .id("gal-2")
                                    .imageUrl("https://storage.souklab.dz/gal2.jpg")
                                    .title("Plat")
                                    .caption("Plat traditionnel")
                                    .displayOrder(1)
                                    .build()
                    ))
                    .certifications(List.of(
                            CertificationResponseDTO.builder()
                                    .id("cert-1")
                                    .title("Carte Artisan CAM")
                                    .issuer("CAM Alger")
                                    .issuedAt(LocalDate.of(2025, 1, 1))
                                    .expiresAt(LocalDate.of(2030, 1, 1))
                                    .documentUrl("/api/v1/files/cert-doc-key")
                                    .isVerified(true)
                                    .build()
                    ))
                    .rating(4.8)
                    .reviewsCount(24)
                    .teacher(true)
                    .verified(true)
                    .contactInfoLocked(false)
                    .name("Ahmed Pottery")
                    .phone("+213555123456")
                    .email("ahmed@example.com")
                    .website("https://pottery.dz")
                    .address("12 Didouche Mourad")
                    .build();

            when(artisanProfileService.getArtisanProfile("artisan-1")).thenReturn(profile);

            mockMvc.perform(get("/api/v1/artisan/artisan-1")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("artisan-1"))
                    .andExpect(jsonPath("$.data.bio").value("Handmade pottery specialist"))
                    .andExpect(jsonPath("$.data.city").value("Algiers"))
                    .andExpect(jsonPath("$.data.regionId").value("reg-16"))
                    .andExpect(jsonPath("$.data.region.id").value("reg-16"))
                    .andExpect(jsonPath("$.data.region.name").value("Alger"))
                    .andExpect(jsonPath("$.data.region.slug").value("alger"))
                    .andExpect(jsonPath("$.data.region.code").value("16"))
                    .andExpect(jsonPath("$.data.subCategoryId").value("sub-4"))
                    .andExpect(jsonPath("$.data.subCategory.id").value("sub-4"))
                    .andExpect(jsonPath("$.data.subCategory.name").value("Poterie"))
                    .andExpect(jsonPath("$.data.subCategory.slug").value("poterie"))
                    .andExpect(jsonPath("$.data.subCategory.categoryId").value("cat-1"))
                    .andExpect(jsonPath("$.data.subCategory.categoryName").value("Artisanat Traditionnel"))
                    .andExpect(jsonPath("$.data.materials[0].id").value("mat-1"))
                    .andExpect(jsonPath("$.data.materials[0].name").value("Argile"))
                    .andExpect(jsonPath("$.data.materials[0].slug").value("argile"))
                    .andExpect(jsonPath("$.data.techniques[0].id").value("tech-1"))
                    .andExpect(jsonPath("$.data.techniques[0].name").value("Modelage"))
                    .andExpect(jsonPath("$.data.techniques[0].slug").value("modelage"))
                    .andExpect(jsonPath("$.data.epoques[0].id").value("epo-1"))
                    .andExpect(jsonPath("$.data.epoques[0].name").value("Contemporain"))
                    .andExpect(jsonPath("$.data.epoques[0].slug").value("contemporain"))
                    .andExpect(jsonPath("$.data.galleryImages[0].id").value("gal-1"))
                    .andExpect(jsonPath("$.data.galleryImages[0].imageUrl").value("https://storage.souklab.dz/gal1.jpg"))
                    .andExpect(jsonPath("$.data.galleryImages[0].title").value("Vase"))
                    .andExpect(jsonPath("$.data.galleryImages[0].caption").value("Argile cuite"))
                    .andExpect(jsonPath("$.data.galleryImages[0].displayOrder").value(0))
                    .andExpect(jsonPath("$.data.galleryImages[1].id").value("gal-2"))
                    .andExpect(jsonPath("$.data.galleryImages[1].imageUrl").value("https://storage.souklab.dz/gal2.jpg"))
                    .andExpect(jsonPath("$.data.galleryImages[1].title").value("Plat"))
                    .andExpect(jsonPath("$.data.galleryImages[1].caption").value("Plat traditionnel"))
                    .andExpect(jsonPath("$.data.galleryImages[1].displayOrder").value(1))
                    .andExpect(jsonPath("$.data.certifications[0].id").value("cert-1"))
                    .andExpect(jsonPath("$.data.certifications[0].title").value("Carte Artisan CAM"))
                    .andExpect(jsonPath("$.data.certifications[0].issuer").value("CAM Alger"))
                    .andExpect(jsonPath("$.data.certifications[0].issuedAt").value("2025-01-01"))
                    .andExpect(jsonPath("$.data.certifications[0].expiresAt").value("2030-01-01"))
                    .andExpect(jsonPath("$.data.certifications[0].documentUrl").value("/api/v1/files/cert-doc-key"))
                    .andExpect(jsonPath("$.data.certifications[0].verified").value(true))
                    .andExpect(jsonPath("$.data.rating").value(4.8))
                    .andExpect(jsonPath("$.data.reviewsCount").value(24))
                    .andExpect(jsonPath("$.data.teacher").value(true))
                    .andExpect(jsonPath("$.data.verified").value(true))
                    .andExpect(jsonPath("$.data.contactInfoLocked").value(false))
                    .andExpect(jsonPath("$.data.name").value("Ahmed Pottery"))
                    .andExpect(jsonPath("$.data.phone").value("+213555123456"))
                    .andExpect(jsonPath("$.data.email").value("ahmed@example.com"))
                    .andExpect(jsonPath("$.data.website").value("https://pottery.dz"))
                    .andExpect(jsonPath("$.data.address").value("12 Didouche Mourad"));

            verify(artisanProfileService).getArtisanProfile("artisan-1");
        }

        /**
         * Verifies that when contact info is locked (e.g. non-premium viewer),
         * the controller correctly serializes contactInfoLocked=true with placeholder name,
         * null sensitive fields, and masked null documentUrl in certifications.
         */
        @Test
        @DisplayName("masked profile serializes contactInfoLocked=true with null contact details returning 200 OK")
        void getArtisanProfile_whenContactInfoLocked_shouldReturnMaskedProfileAnd200Ok() throws Exception {
            ArtisanPublicViewDTO maskedProfile = ArtisanPublicViewDTO.builder()
                    .id("artisan-1")
                    .bio("Handmade pottery specialist")
                    .city("Algiers")
                    .regionId("reg-16")
                    .subCategoryId("sub-4")
                    .certifications(List.of(
                            CertificationResponseDTO.builder()
                                    .id("cert-1")
                                    .title("Carte Artisan CAM")
                                    .issuer("CAM Alger")
                                    .issuedAt(LocalDate.of(2025, 1, 1))
                                    .expiresAt(LocalDate.of(2030, 1, 1))
                                    .documentUrl(null)
                                    .isVerified(true)
                                    .build()
                    ))
                    .rating(4.8)
                    .reviewsCount(24)
                    .teacher(true)
                    .verified(true)
                    .contactInfoLocked(true)
                    .name("Artisan #SAN-1")
                    .phone(null)
                    .email(null)
                    .website(null)
                    .address(null)
                    .build();

            when(artisanProfileService.getArtisanProfile("artisan-1")).thenReturn(maskedProfile);

            mockMvc.perform(get("/api/v1/artisan/artisan-1")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("artisan-1"))
                    .andExpect(jsonPath("$.data.contactInfoLocked").value(true))
                    .andExpect(jsonPath("$.data.name").value("Artisan #SAN-1"))
                    .andExpect(jsonPath("$.data.phone").value(nullValue()))
                    .andExpect(jsonPath("$.data.email").value(nullValue()))
                    .andExpect(jsonPath("$.data.website").value(nullValue()))
                    .andExpect(jsonPath("$.data.address").value(nullValue()))
                    .andExpect(jsonPath("$.data.certifications[0].id").value("cert-1"))
                    .andExpect(jsonPath("$.data.certifications[0].documentUrl").value(nullValue()))
                    .andExpect(jsonPath("$.data.certifications[0].verified").value(true));

            verify(artisanProfileService).getArtisanProfile("artisan-1");
        }

        /**
         * Verifies that ResourceNotFoundException from service maps to 404 Not Found.
         */
        @Test
        @DisplayName("artisan not found maps to 404 Not Found")
        void getArtisanProfile_whenNotFound_shouldReturn404NotFound() throws Exception {
            when(artisanProfileService.getArtisanProfile("artisan-999"))
                    .thenThrow(new ResourceNotFoundException("Artisan not found with id: artisan-999"));

            mockMvc.perform(get("/api/v1/artisan/artisan-999")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("Artisan not found with id: artisan-999"));
        }

        /**
         * Verifies that ForbiddenException from service (e.g. unverified email viewer) maps to 403 Forbidden.
         */
        @Test
        @DisplayName("forbidden viewer condition maps to 403 Forbidden")
        void getArtisanProfile_whenViewerForbidden_shouldReturn403Forbidden() throws Exception {
            when(artisanProfileService.getArtisanProfile("artisan-1"))
                    .thenThrow(new ForbiddenException("Please verify your email address to access artisan profiles."));

            mockMvc.perform(get("/api/v1/artisan/artisan-1")
                            .with(client()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("Please verify your email address to access artisan profiles."));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized from security filter chain.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getArtisanProfile_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/artisan/artisan-1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }
}
