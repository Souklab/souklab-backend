package com.project.souklab.service.profile;

import com.project.souklab.dto.auth.UserSummaryDTO;
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanCertification;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientType;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProfileResponseMapper}.
 * Verifies transformation of {@link User}, {@link Artisan}, and {@link Client} domain entities
 * into role-specific {@link ProfileResponse} subtypes and admin-facing {@link UserSummaryDTO}.
 */
class ProfileResponseMapperTest {

    private ProfileResponseMapper mapper;
    private AuthorizationPermission artisanContentPermission;
    private AuthorizationPermission profileReadPermission;

    /**
     * Initializes test fixtures and mapper instance.
     */
    @BeforeEach
    void setUp() {
        mapper = new ProfileResponseMapper();

        artisanContentPermission = new AuthorizationPermission();
        artisanContentPermission.setPermissionKey(Permission.Artisan.CONTENT.value());

        profileReadPermission = new AuthorizationPermission();
        profileReadPermission.setPermissionKey(Permission.Profile.READ.value());
    }

    /**
     * Verifies mapToProfileResponse returns ArtisanResponseDTO with default values when Artisan entity is null.
     */
    @Test
    @DisplayName("mapToProfileResponse: returns ArtisanResponseDTO with defaults when Artisan entity is null")
    void mapToProfileResponse_forArtisanWithNullArtisanEntity_returnsDefaults() {
        User user = User.builder()
                .email("artisan@example.com")
                .firstName("Ali")
                .lastName("K")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(null)
                .build();

        ProfileResponse response = mapper.mapToProfileResponse(user);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO dto = (ArtisanResponseDTO) response;
        assertThat(dto.getBio()).isNull();
        assertThat(dto.getRating()).isEqualTo(0.0);
        assertThat(dto.getReviewsCount()).isZero();
        assertThat(dto.isTeacher()).isFalse();
        assertThat(dto.isVerified()).isFalse();
        assertThat(dto.isPremium()).isFalse();
    }

    @Test
    void mapToProfileResponse_forArtisanWithUninitializedCollectionsUsesEmptyCollections() {
        Artisan artisan = Artisan.builder().build();
        artisan.setMaterials(null);
        artisan.setTechniques(null);
        artisan.setEpoques(null);
        artisan.setGalleryImages(null);
        artisan.setCertifications(null);
        User user = User.builder()
                .email("artisan-empty@example.com")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(artisan)
                .build();

        ArtisanResponseDTO dto = (ArtisanResponseDTO) mapper.mapToProfileResponse(user);
        assertThat(dto.getMaterials()).isEmpty();
        assertThat(dto.getTechniques()).isEmpty();
        assertThat(dto.getEpoques()).isEmpty();
        assertThat(dto.getGalleryImages()).isEmpty();
        assertThat(dto.getCertifications()).isEmpty();
    }

    /**
     * Verifies mapToProfileResponse returns ArtisanResponseDTO with full values when Artisan entity is populated.
     */
    @Test
    @DisplayName("mapToProfileResponse: returns ArtisanResponseDTO with populated entity fields")
    void mapToProfileResponse_forArtisanWithPopulatedArtisanEntity_returnsMappedFields() {
        Region region = Region.builder().name("Alger").code("16").build();
        region.setId("reg-16");
        JobSubCategory subCategory = JobSubCategory.builder().name("Pottery").build();
        subCategory.setId("sub-pottery");
        Material material = Material.builder().name("Clay").build();
        material.setId("mat-clay");
        Technique technique = Technique.builder().name("Wheel Throwing").build();
        technique.setId("tech-throwing");
        Epoque epoque = Epoque.builder().name("Ottoman").build();
        epoque.setId("ep-ottoman");

        ArtisanGalleryImage galleryImage = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/img1.jpg")
                .displayOrder(1)
                .build();
        galleryImage.setId("gal-1");

        ArtisanCertification certification = ArtisanCertification.builder()
                .title("CAM Master Craftsman")
                .documentUrl("https://storage.souklab.dz/cert1.pdf")
                .build();
        certification.setId("cert-1");

        Artisan artisan = Artisan.builder()
                .bio("Master potter")
                .city("Safi")
                .region(region)
                .subCategory(subCategory)
                .materials(new HashSet<>(Set.of(material)))
                .techniques(new HashSet<>(Set.of(technique)))
                .epoques(new HashSet<>(Set.of(epoque)))
                .galleryImages(List.of(galleryImage))
                .certifications(List.of(certification))
                .rating(0.0)
                .reviewsCount(0)
                .isTeacher(true)
                .isVerified(true)
                .isPremium(true)
                .build();

        User user = User.builder()
                .email("artisan@example.com")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(artisan)
                .build();

        ProfileResponse response = mapper.mapToProfileResponse(user);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO dto = (ArtisanResponseDTO) response;
        assertThat(dto.getBio()).isEqualTo("Master potter");
        assertThat(dto.getRating()).isZero();
        assertThat(dto.getReviewsCount()).isZero();
        assertThat(dto.isTeacher()).isTrue();
        assertThat(dto.isVerified()).isTrue();
        assertThat(dto.isPremium()).isTrue();
        assertThat(dto.getRegion()).isNotNull();
        assertThat(dto.getRegion().getName()).isEqualTo("Alger");
        assertThat(dto.getSubCategory()).isNotNull();
        assertThat(dto.getSubCategory().getName()).isEqualTo("Pottery");
        assertThat(dto.getMaterials()).hasSize(1);
        assertThat(dto.getTechniques()).hasSize(1);
        assertThat(dto.getEpoques()).hasSize(1);
        assertThat(dto.getGalleryImages()).hasSize(1);
        assertThat(dto.getCertifications()).hasSize(1);
    }

    /**
     * Verifies mapToProfileResponse filters out soft-deleted gallery images and certifications.
     */
    @Test
    @DisplayName("mapToProfileResponse: filters out soft-deleted gallery images and certifications")
    void mapToProfileResponse_filtersSoftDeletedGalleryImagesAndCertifications() {
        ArtisanGalleryImage activeImage = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/active.jpg")
                .displayOrder(1)
                .build();
        activeImage.setId("gal-active");
        activeImage.setDeletedAt(null);

        ArtisanGalleryImage deletedImage = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/deleted.jpg")
                .displayOrder(0)
                .build();
        deletedImage.setId("gal-deleted");
        deletedImage.setDeletedAt(LocalDateTime.now());

        ArtisanCertification activeCert = ArtisanCertification.builder()
                .title("Active Cert")
                .build();
        activeCert.setId("cert-active");
        activeCert.setDeletedAt(null);

        ArtisanCertification deletedCert = ArtisanCertification.builder()
                .title("Deleted Cert")
                .build();
        deletedCert.setId("cert-deleted");
        deletedCert.setDeletedAt(LocalDateTime.now());

        Artisan artisan = Artisan.builder()
                .galleryImages(List.of(activeImage, deletedImage))
                .certifications(List.of(activeCert, deletedCert))
                .build();

        User user = User.builder()
                .email("artisan@example.com")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(artisan)
                .build();

        ProfileResponse response = mapper.mapToProfileResponse(user);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO dto = (ArtisanResponseDTO) response;
        assertThat(dto.getGalleryImages()).hasSize(1);
        assertThat(dto.getGalleryImages().get(0).getId()).isEqualTo("gal-active");
        assertThat(dto.getCertifications()).hasSize(1);
        assertThat(dto.getCertifications().get(0).getId()).isEqualTo("cert-active");
    }

    /**
     * Verifies mapToProfileResponse returns ClientProfileResponseDTO with default clientType INDIVIDUAL when Client is null.
     */
    @Test
    @DisplayName("mapToProfileResponse: returns ClientProfileResponseDTO with INDIVIDUAL default when Client is null")
    void mapToProfileResponse_forClientWithNullClientEntity_returnsDefaults() {
        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(profileReadPermission)))
                .client(null)
                .build();

        ProfileResponse response = mapper.mapToProfileResponse(user);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ClientProfileResponseDTO dto = (ClientProfileResponseDTO) response;
        assertThat(dto.getClientType()).isEqualTo(ClientType.INDIVIDUAL);
        assertThat(dto.getCompanyName()).isNull();
    }

    /**
     * Verifies mapToProfileResponse returns ClientProfileResponseDTO with populated Client fields.
     */
    @Test
    @DisplayName("mapToProfileResponse: returns ClientProfileResponseDTO with populated fields")
    void mapToProfileResponse_forClientWithPopulatedClientEntity_returnsMappedFields() {
        Client client = Client.builder()
                .companyName("Heritage Imports")
                .clientType(ClientType.BUSINESS.value())
                .city("Tangier")
                .build();

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(profileReadPermission)))
                .client(client)
                .build();

        ProfileResponse response = mapper.mapToProfileResponse(user);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ClientProfileResponseDTO dto = (ClientProfileResponseDTO) response;
        assertThat(dto.getCompanyName()).isEqualTo("Heritage Imports");
        assertThat(dto.getClientType()).isEqualTo(ClientType.BUSINESS);
        assertThat(dto.getCity()).isEqualTo("Tangier");
    }

    /**
     * Verifies mapToSummaryDTO maps artisan role, teaching flag, verification, and premium flags.
     */
    @Test
    @DisplayName("mapToSummaryDTO: maps artisan flags and permissions correctly")
    void mapToSummaryDTO_withArtisanAndFlags_mapsExpectedSummary() {
        Artisan artisan = Artisan.builder()
                .isTeacher(true)
                .isPremium(true)
                .isVerified(true)
                .build();

        User user = User.builder()
                .email("artisan@example.com")
                .firstName("Rachid")
                .lastName("M")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(artisan)
                .build();

        UserSummaryDTO summary = mapper.mapToSummaryDTO(user);

        assertThat(summary.getEmail()).isEqualTo("artisan@example.com");
        assertThat(summary.getPermissions()).contains(Permission.Artisan.CONTENT);
        assertThat(summary.isTeacher()).isTrue();
        assertThat(summary.isPremium()).isTrue();
        assertThat(summary.isValidated()).isTrue();
    }

    /**
     * Verifies mapToSummaryDTO maps client flags correctly.
     */
    @Test
    @DisplayName("mapToSummaryDTO: maps client flags correctly")
    void mapToSummaryDTO_withClientAndFlags_mapsExpectedSummary() {
        Client client = Client.builder()
                .isPremium(true)
                .isVerified(true)
                .build();

        User user = User.builder()
                .email("client@example.com")
                .firstName("Fatima")
                .lastName("Z")
                .permissions(new HashSet<>(Set.of(profileReadPermission)))
                .client(client)
                .build();

        UserSummaryDTO summary = mapper.mapToSummaryDTO(user);

        assertThat(summary.getPermissions()).contains(Permission.Profile.READ);
        assertThat(summary.isTeacher()).isFalse();
        assertThat(summary.isPremium()).isTrue();
        assertThat(summary.isValidated()).isTrue();
    }

    /**
     * Verifies mapToSummaryDTO exposes no permissions when no capabilities are assigned.
     */
    @Test
    @DisplayName("mapToSummaryDTO: leaves permissions empty when no capabilities are assigned")
    void mapToSummaryDTO_withNoRoles_defaultsPrimaryRoleToClient() {
        User user = User.builder()
                .email("noroles@example.com")
                .permissions(new HashSet<>())
                .build();

        UserSummaryDTO summary = mapper.mapToSummaryDTO(user);

        assertThat(summary.getPermissions()).isEmpty();
    }

    /**
     * Verifies mapToSummaryDTO correctly maps false flags for artisan when teacher, premium, and verified are false.
     */
    @Test
    @DisplayName("mapToSummaryDTO: maps all flags as false when artisan has no teacher/premium/verified status")
    void mapToSummaryDTO_withArtisanAllFlagsFalse_mapsExpectedSummary() {
        Artisan artisan = Artisan.builder()
                .isTeacher(false)
                .isPremium(false)
                .isVerified(false)
                .build();

        User user = User.builder()
                .email("plainartisan@example.com")
                .firstName("Hassan")
                .lastName("B")
                .permissions(new HashSet<>(Set.of(artisanContentPermission)))
                .artisan(artisan)
                .build();

        UserSummaryDTO summary = mapper.mapToSummaryDTO(user);

        assertThat(summary.isTeacher()).isFalse();
        assertThat(summary.isPremium()).isFalse();
        assertThat(summary.isValidated()).isFalse();
    }

    /**
     * Verifies mapToSummaryDTO correctly maps false flags for client when premium and verified are false.
     */
    @Test
    @DisplayName("mapToSummaryDTO: maps all flags as false when client has no premium/verified status")
    void mapToSummaryDTO_withClientAllFlagsFalse_mapsExpectedSummary() {
        Client client = Client.builder()
                .isPremium(false)
                .isVerified(false)
                .build();

        User user = User.builder()
                .email("plainclient@example.com")
                .firstName("Mona")
                .lastName("S")
                .permissions(new HashSet<>(Set.of(profileReadPermission)))
                .client(client)
                .build();

        UserSummaryDTO summary = mapper.mapToSummaryDTO(user);

        assertThat(summary.isTeacher()).isFalse();
        assertThat(summary.isPremium()).isFalse();
        assertThat(summary.isValidated()).isFalse();
    }
}
