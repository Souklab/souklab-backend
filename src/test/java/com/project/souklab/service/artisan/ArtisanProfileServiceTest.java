package com.project.souklab.service.artisan;

import org.mockito.Mockito;

import com.project.souklab.dao.ArtisanCertificationRepository;
import com.project.souklab.dao.ArtisanGalleryImageRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.profile.ArtisanPublicViewDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanCertification;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.Client;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import com.project.souklab.security.ViewerPremiumResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive unit test suite for ArtisanProfileService covering authentication,
 * status and email verification gating, view count tracking, privacy contact masking,
 * taxonomy summary assembly, showcase gallery images, and official certifications.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private ArtisanProfileViewRecorder artisanProfileViewRecorder;

    @Mock
    private ArtisanGalleryImageRepository artisanGalleryImageRepository;

    @Mock
    private ArtisanCertificationRepository artisanCertificationRepository;

    @Mock
    private ViewerPremiumResolver viewerPremiumResolver;

    @InjectMocks
    private ArtisanProfileService artisanProfileService;

    private User targetUser;
    private Artisan targetArtisan;
    private Region region;
    private JobCategory category;
    private JobSubCategory subCategory;
    private Material material;
    private Technique technique;
    private Epoque epoque;

    /**
     * Creates a test AuthorizationPermission entity with the specified name.
     */
    private AuthorizationPermission createPermission(Permission permission) {
        AuthorizationPermission entity = new AuthorizationPermission();
        entity.setPermissionKey(permission.value());
        entity.setEnabled(true);
        return entity;
    }

    /**
     * Initializes test entities and security context preconditions before each test execution.
     */
    @BeforeEach
    void setUp() {
        region = Region.builder()
                .name("Tizi Ouzou")
                .slug("tizi-ouzou")
                .code("15")
                .build();
        region.setId("reg-15");

        category = JobCategory.builder()
                .name("Jewelry & Metalsmithing")
                .slug("jewelry-metalsmithing")
                .build();
        category.setId("cat-1");

        subCategory = JobSubCategory.builder()
                .name("Kabyle Silver Jewelry")
                .slug("kabyle-silver-jewelry")
                .category(category)
                .build();
        subCategory.setId("subcat-10");

        material = Material.builder()
                .name("Coral")
                .slug("coral")
                .build();
        material.setId("mat-coral");

        technique = Technique.builder()
                .name("Cloisonné Enameling")
                .slug("cloisonne-enameling")
                .build();
        technique.setId("tech-enamel");

        epoque = Epoque.builder()
                .name("Kabyle Heritage")
                .slug("kabyle-heritage")
                .periodEra("Traditional")
                .build();
        epoque.setId("epo-kabyle");

        targetUser = User.builder()
                .email("artisan@souklab.dz")
                .firstName("Djamel")
                .lastName("Beni Yenni")
                .phone("+213550123456")
                .avatarUrl("https://storage.souklab.dz/avatars/djamel.jpg")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        targetUser.setId("artisan-user-id");

        targetArtisan = Artisan.builder()
                .user(targetUser)
                .bio("Master silver craftsman from Beni Yenni")
                .city("Beni Yenni")
                .region(region)
                .subCategory(subCategory)
                .materials(Set.of(material))
                .techniques(Set.of(technique))
                .epoques(Set.of(epoque))
                .website("https://djamel-bijoux.dz")
                .address("Village Taourirt Mimoun")
                .rating(4.9)
                .reviewsCount(58)
                .viewsCount(120)
                .isTeacher(true)
                .isVerified(true)
                .build();
        targetArtisan.setId("artisan-user-id");
    }

    /**
     * Clears authentication from SecurityContextHolder after each test execution.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Helper method to authenticate a viewer with given email and permissions.
     */
    private void setAuthenticatedViewer(String email, Permission... permissions) {
        List<Permission> authorities = List.of(permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "cred", authorities)
        );
    }

    /**
     * Verifies getArtisanProfile throws UnauthorizedException when no authenticated principal exists.
     */
    @Test
    @DisplayName("getArtisanProfile: throws UnauthorizedException when unauthenticated")
    void getArtisanProfile_whenUnauthenticated_throwsUnauthorizedException() {
        assertThatThrownBy(() -> artisanProfileService.getArtisanProfile("artisan-user-id"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated.");
    }

    /**
     * Verifies getArtisanProfile throws ResourceNotFoundException when authenticated viewer is not in DB.
     */
    @Test
    @DisplayName("getArtisanProfile: throws ResourceNotFoundException when viewer user not found")
    void getArtisanProfile_whenViewerNotFound_throwsResourceNotFoundException() {
        setAuthenticatedViewer("viewer@souklab.dz", Permission.Profile.READ);
        when(userRepository.findByEmail("viewer@souklab.dz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> artisanProfileService.getArtisanProfile("artisan-user-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: viewer@souklab.dz");
    }

    /**
     * Verifies getArtisanProfile throws ForbiddenException when non-admin viewer status is not ACTIVE.
     */
    @Test
    @DisplayName("getArtisanProfile: throws ForbiddenException when non-admin viewer is not active")
    void getArtisanProfile_whenViewerNotActive_throwsForbiddenException() {
        setAuthenticatedViewer("viewer@souklab.dz", Permission.Profile.READ);
        User viewer = User.builder()
                .email("viewer@souklab.dz")
                .status(AccountStatus.PENDING)
                .emailVerified(true)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        when(userRepository.findByEmail("viewer@souklab.dz")).thenReturn(Optional.of(viewer));

        assertThatThrownBy(() -> artisanProfileService.getArtisanProfile("artisan-user-id"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Your account is not active.");
    }

    /**
     * Verifies getArtisanProfile throws ForbiddenException when non-admin viewer email is not verified.
     */
    @Test
    @DisplayName("getArtisanProfile: throws ForbiddenException when non-admin viewer email is unverified")
    void getArtisanProfile_whenViewerEmailNotVerified_throwsForbiddenException() {
        setAuthenticatedViewer("viewer@souklab.dz", Permission.Profile.READ);
        User viewer = User.builder()
                .email("viewer@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(false)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        when(userRepository.findByEmail("viewer@souklab.dz")).thenReturn(Optional.of(viewer));

        assertThatThrownBy(() -> artisanProfileService.getArtisanProfile("artisan-user-id"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Please verify your email address to access artisan profiles.");
    }

    /**
     * Verifies getArtisanProfile throws ResourceNotFoundException when target artisan ID does not exist.
     */
    @Test
    @DisplayName("getArtisanProfile: throws ResourceNotFoundException when target artisan does not exist")
    void getArtisanProfile_whenArtisanNotFound_throwsResourceNotFoundException() {
        setAuthenticatedViewer("admin@souklab.dz", Permission.Admin.USERS);
        User admin = User.builder()
                .email("admin@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .permissions(Set.of(createPermission(Permission.Admin.USERS)))
                .build();
        when(userRepository.findByEmail("admin@souklab.dz")).thenReturn(Optional.of(admin));
        when(artisanRepository.findById("missing-artisan")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> artisanProfileService.getArtisanProfile("missing-artisan"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: missing-artisan");
    }

    /**
     * Verifies getArtisanProfile unmasks contact info for self-viewer and skips view count recording.
     */
    @Test
    @DisplayName("getArtisanProfile: self-viewer sees unmasked contact info without incrementing views")
    void getArtisanProfile_whenViewerIsSelf_unmasksContactInfoAndDoesNotIncrementViewCount() {
        setAuthenticatedViewer("artisan@souklab.dz", Permission.Artisan.CONTENT);
        targetUser.setPermissions(Set.of(createPermission(Permission.Artisan.CONTENT)));

        when(userRepository.findByEmail("artisan@souklab.dz")).thenReturn(Optional.of(targetUser));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(Collections.emptyList());

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");

        assertThat(result.isContactInfoLocked()).isFalse();
        assertThat(result.getName()).isEqualTo("Djamel Beni Yenni");
        assertThat(result.getPhone()).isEqualTo("+213550123456");
        assertThat(result.getEmail()).isEqualTo("artisan@souklab.dz");
        assertThat(result.getWebsite()).isEqualTo("https://djamel-bijoux.dz");
        assertThat(result.getAddress()).isEqualTo("Village Taourirt Mimoun");

        verify(artisanProfileViewRecorder).recordProfileViewIfEligible(targetUser, targetArtisan, true, false);
    }

    /**
     * Verifies getArtisanProfile unmasks contact info for admin viewer and skips view count recording.
     */
    @Test
    @DisplayName("getArtisanProfile: admin viewer sees unmasked contact info without incrementing views")
    void getArtisanProfile_whenViewerIsAdmin_unmasksContactInfoAndDoesNotIncrementViewCount() {
        setAuthenticatedViewer("admin@souklab.dz", Permission.Admin.USERS);
        User admin = User.builder()
                .email("admin@souklab.dz")
                .status(AccountStatus.SUSPENDED)
                .emailVerified(false)
                .permissions(Set.of(createPermission(Permission.Admin.USERS)))
                .build();
        admin.setId("admin-id");

        when(userRepository.findByEmail("admin@souklab.dz")).thenReturn(Optional.of(admin));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(Collections.emptyList());

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");

        assertThat(result.isContactInfoLocked()).isFalse();
        assertThat(result.getName()).isEqualTo("Djamel Beni Yenni");
        verify(artisanProfileViewRecorder).recordProfileViewIfEligible(admin, targetArtisan, false, true);
    }

    /**
     * Verifies getArtisanProfile masks contact info and hides certification documentUrl for non-premium viewer.
     */
    @Test
    @DisplayName("getArtisanProfile: non-premium viewer receives masked contact info and masked certification URLs")
    void getArtisanProfile_whenViewerIsNonPremium_masksContactInfoAndNullsDocumentUrl() {
        setAuthenticatedViewer("client@souklab.dz", Permission.Profile.READ);
        Client client = Client.builder().isPremium(false).build();
        User viewer = User.builder()
                .email("client@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .client(client)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        viewer.setId("client-viewer-id");

        ArtisanGalleryImage image = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/showcase/bracelet.jpg")
                .title("Kabyle Silver Bracelet")
                .caption("Handcrafted silver with coral cabochons")
                .displayOrder(1)
                .build();
        image.setId("img-1");

        ArtisanCertification cert = ArtisanCertification.builder()
                .title("CAM Master Craftsman Accreditation")
                .issuer("Chambre de l'Artisanat et des Métiers de Tizi Ouzou")
                .issuedAt(LocalDate.of(2023, 5, 10))
                .isVerified(true)
                .documentUrl("https://storage.souklab.dz/private/cam-card.pdf")
                .build();
        cert.setId("cert-1");

        when(userRepository.findByEmail("client@souklab.dz")).thenReturn(Optional.of(viewer));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(List.of(image));
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(List.of(cert));
        // Delegate to shared resolver: non-premium client → locked
        when(viewerPremiumResolver.isContactInfoLocked(any(User.class), Mockito.eq(false), Mockito.eq(false)))
                .thenReturn(true);

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");

        assertThat(result.isContactInfoLocked()).isTrue();
        assertThat(result.getName()).startsWith("Artisan #");
        assertThat(result.getPhone()).isNull();
        assertThat(result.getEmail()).isNull();
        assertThat(result.getWebsite()).isNull();
        assertThat(result.getAddress()).isNull();

        assertThat(result.getRegion()).isNotNull();
        assertThat(result.getRegion().getName()).isEqualTo("Tizi Ouzou");
        assertThat(result.getSubCategory().getName()).isEqualTo("Kabyle Silver Jewelry");
        assertThat(result.getMaterials()).hasSize(1);
        assertThat(result.getTechniques()).hasSize(1);
        assertThat(result.getEpoques()).hasSize(1);

        assertThat(result.getGalleryImages()).hasSize(1);
        assertThat(result.getGalleryImages().get(0).getImageUrl()).isEqualTo("https://storage.souklab.dz/showcase/bracelet.jpg");

        assertThat(result.getCertifications()).hasSize(1);
        assertThat(result.getCertifications().get(0).isVerified()).isTrue();
        assertThat(result.getCertifications().get(0).getDocumentUrl()).isNull();

        verify(artisanProfileViewRecorder).recordProfileViewIfEligible(viewer, targetArtisan, false, false);
    }

    /**
     * Verifies getArtisanProfile unmasks full contact info and documentUrl for premium client viewer.
     */
    @Test
    @DisplayName("getArtisanProfile: premium client viewer receives unmasked contact info and certification URLs")
    void getArtisanProfile_whenViewerIsPremiumClient_unmasksAllFields() {
        setAuthenticatedViewer("premium@souklab.dz", Permission.Profile.READ);
        Client client = Client.builder().isPremium(true).build();
        User viewer = User.builder()
                .email("premium@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .client(client)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        viewer.setId("premium-viewer-id");

        ArtisanCertification cert = ArtisanCertification.builder()
                .title("CAM Master Craftsman Accreditation")
                .issuer("CAM Tizi Ouzou")
                .issuedAt(LocalDate.of(2023, 5, 10))
                .isVerified(true)
                .documentUrl("https://storage.souklab.dz/private/cam-card.pdf")
                .build();
        cert.setId("cert-1");

        when(userRepository.findByEmail("premium@souklab.dz")).thenReturn(Optional.of(viewer));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(List.of(cert));

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");

        assertThat(result.isContactInfoLocked()).isFalse();
        assertThat(result.getName()).isEqualTo("Djamel Beni Yenni");
        assertThat(result.getPhone()).isEqualTo("+213550123456");
        assertThat(result.getEmail()).isEqualTo("artisan@souklab.dz");
        assertThat(result.getWebsite()).isEqualTo("https://djamel-bijoux.dz");
        assertThat(result.getAddress()).isEqualTo("Village Taourirt Mimoun");

        assertThat(result.getCertifications()).hasSize(1);
        assertThat(result.getCertifications().get(0).getDocumentUrl()).isEqualTo("https://storage.souklab.dz/private/cam-card.pdf");

        verify(artisanProfileViewRecorder).recordProfileViewIfEligible(viewer, targetArtisan, false, false);
    }

    @Test
    @DisplayName("getArtisanProfile: premium artisan viewer is allowed to see contact information")
    void getArtisanProfile_whenViewerIsPremiumArtisan_unmasksContactInfo() {
        setAuthenticatedViewer("premium-artisan@souklab.dz", Permission.Artisan.CONTENT);
        Artisan viewerArtisan = Artisan.builder().isPremium(true).build();
        User viewer = User.builder()
                .email("premium-artisan@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .artisan(viewerArtisan)
                .permissions(Set.of(createPermission(Permission.Artisan.CONTENT)))
                .build();
        viewer.setId("premium-artisan-viewer-id");

        when(userRepository.findByEmail("premium-artisan@souklab.dz")).thenReturn(Optional.of(viewer));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                "artisan-user-id")).thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                "artisan-user-id")).thenReturn(Collections.emptyList());

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");

        assertThat(result.isContactInfoLocked()).isFalse();
        assertThat(result.getPhone()).isEqualTo("+213550123456");
        assertThat(result.getEmail()).isEqualTo("artisan@souklab.dz");
    }

    @Test
    @DisplayName("getArtisanProfile: maps an incomplete artisan profile without linked user or taxonomy collections")
    void getArtisanProfile_whenTargetProfileHasOptionalDataMissing_mapsSafeDefaults() {
        setAuthenticatedViewer("admin@souklab.dz", Permission.Admin.USERS);
        User admin = User.builder()
                .email("admin@souklab.dz")
                .status(AccountStatus.SUSPENDED)
                .emailVerified(false)
                .permissions(Set.of(createPermission(Permission.Admin.USERS)))
                .build();
        admin.setId("admin-id");
        targetArtisan.setId("x");
        targetArtisan.setUser(null);
        targetArtisan.setMaterials(null);
        targetArtisan.setTechniques(null);
        targetArtisan.setEpoques(null);

        when(userRepository.findByEmail("admin@souklab.dz")).thenReturn(Optional.of(admin));
        when(artisanRepository.findById("x")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("x"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("x"))
                .thenReturn(Collections.emptyList());

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("x");

        assertThat(result.getName()).isNull();
        assertThat(result.getAvatarUrl()).isNull();
        assertThat(result.getMaterials()).isEmpty();
        assertThat(result.getTechniques()).isEmpty();
        assertThat(result.getEpoques()).isEmpty();
        assertThat(result.isContactInfoLocked()).isFalse();
    }

    @Test
    @DisplayName("getArtisanProfile: masks short identifiers and safely derives a partial display name")
    void getArtisanProfile_whenMaskedIdIsShortAndTargetNameIsPartial_usesSafeFallbacks() {
        setAuthenticatedViewer("client@souklab.dz", Permission.Profile.READ);
        Client client = Client.builder().isPremium(false).build();
        User viewer = User.builder()
                .email("client@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .client(client)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        viewer.setId("client-id");

        targetArtisan.setId("x");
        targetArtisan.setUser(targetUser);

        when(userRepository.findByEmail("client@souklab.dz")).thenReturn(Optional.of(viewer));
        when(artisanRepository.findById("x")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("x"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("x"))
                .thenReturn(Collections.emptyList());
        // Delegate to shared resolver: non-premium client → locked
        when(viewerPremiumResolver.isContactInfoLocked(any(User.class), Mockito.eq(false), Mockito.eq(false)))
                .thenReturn(true);

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("x");

        assertThat(result.isContactInfoLocked()).isTrue();
        assertThat(result.getName()).isEqualTo("Artisan #X");
    }

    @Test
    @DisplayName("getArtisanProfile: derives a display name from a partial target name")
    void getArtisanProfile_whenTargetNameIsBlank_usesAvailableNameParts() {
        setAuthenticatedViewer("admin@souklab.dz", Permission.Admin.USERS);
        User admin = User.builder()
                .email("admin@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .permissions(Set.of(createPermission(Permission.Admin.USERS)))
                .build();
        admin.setId("admin-id");
        User partialTarget = Mockito.mock(User.class);
        when(partialTarget.getName()).thenReturn("");
        when(partialTarget.getFirstName()).thenReturn(null);
        when(partialTarget.getLastName()).thenReturn("Solo");
        when(partialTarget.getAvatarUrl()).thenReturn(null);
        targetArtisan.setUser(partialTarget);

        when(userRepository.findByEmail("admin@souklab.dz")).thenReturn(Optional.of(admin));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(Collections.emptyList());

        assertThat(artisanProfileService.getArtisanProfile("artisan-user-id").getName())
                .isEqualTo("Solo");
    }

    @Test
    @DisplayName("getArtisanProfile: delegates view recording to artisanProfileViewRecorder")
    void getArtisanProfile_delegatesViewRecordingToRecorder() {
        setAuthenticatedViewer("client@souklab.dz", Permission.Profile.READ);
        User viewer = User.builder()
                .email("client@souklab.dz")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .permissions(Set.of(createPermission(Permission.Profile.READ)))
                .build();
        viewer.setId("client-viewer-id");

        when(userRepository.findByEmail("client@souklab.dz")).thenReturn(Optional.of(viewer));
        when(artisanRepository.findById("artisan-user-id")).thenReturn(Optional.of(targetArtisan));
        when(viewerPremiumResolver.isContactInfoLocked(viewer, false, false)).thenReturn(true);
        when(artisanGalleryImageRepository.findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc("artisan-user-id"))
                .thenReturn(Collections.emptyList());
        when(artisanCertificationRepository.findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc("artisan-user-id"))
                .thenReturn(Collections.emptyList());

        ArtisanPublicViewDTO result = artisanProfileService.getArtisanProfile("artisan-user-id");
        assertThat(result).isNotNull();
        verify(artisanProfileViewRecorder).recordProfileViewIfEligible(viewer, targetArtisan, false, false);
    }
}
