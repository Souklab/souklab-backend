package com.project.souklab.service.profile;

import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dao.EpoqueRepository;
import com.project.souklab.dao.JobSubCategoryRepository;
import com.project.souklab.dao.MaterialRepository;
import com.project.souklab.dao.RegionRepository;
import com.project.souklab.dao.TechniqueRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.access.MeCapabilitiesContributor;
import com.project.souklab.dto.auth.CompleteProfileRequestDTO;
import com.project.souklab.dto.common.PatchField;
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.dto.profile.UserPatchDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.Artisan;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit test suite for {@link ProfileService} covering profile lifecycle management,
 * taxonomy resolution, partial patching, and error propagation across artisan and client permissions.
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private JobSubCategoryRepository jobSubCategoryRepository;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private TechniqueRepository techniqueRepository;

    @Mock
    private EpoqueRepository epoqueRepository;

    private final ProfileResponseMapper profileResponseMapper = ProfileResponseMapperTestFactory.create();
    @Mock
    private MeCapabilitiesContributor meCapabilitiesContributor;

    private ProfileService profileService;
    private AuthorizationPermission artisanRole;
    private AuthorizationPermission clientRole;

    /**
     * Initializes test fixtures and ProfileService instance under test.
     */
    @BeforeEach
    void setUp() {
        artisanRole = new AuthorizationPermission();
        artisanRole.setPermissionKey(Permission.Artisan.CONTENT.value());
        artisanRole.setDescription("Artisan role");

        clientRole = new AuthorizationPermission();
        clientRole.setPermissionKey(Permission.Profile.READ.value());
        clientRole.setDescription("Client role");

        profileService = new ProfileService(
                userRepository,
                artisanRepository,
                clientRepository,
                regionRepository,
                jobSubCategoryRepository,
                materialRepository,
                techniqueRepository,
                epoqueRepository,
                profileResponseMapper,
                meCapabilitiesContributor
        );
    }

    /**
     * Clears SecurityContextHolder to prevent cross-test authentication leakage.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Verifies getCurrentUser throws UnauthorizedException when unauthenticated.
     */
    @Test
    @DisplayName("getCurrentUser: throws UnauthorizedException when unauthenticated")
    void getCurrentUser_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> profileService.getCurrentUser())
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated.");
    }

    /**
     * Verifies getCurrentUser throws ResourceNotFoundException when authenticated username is not in DB.
     */
    @Test
    @DisplayName("getCurrentUser: throws ResourceNotFoundException when authenticated user not in DB")
    void getCurrentUser_whenUserNotFoundInRepository_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("missing@example.com", "cred", List.of())
        );

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.getCurrentUser())
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: missing@example.com");
    }

    /**
     * Verifies getCurrentUser returns ArtisanResponseDTO for authenticated artisan.
     */
    @Test
    @DisplayName("getCurrentUser: returns ArtisanResponseDTO for authenticated artisan")
    void getCurrentUser_withAuthenticatedArtisan_returnsArtisanProfileResponse() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        Artisan artisan = Artisan.builder()
                .bio("Master woodworker")
                .city("Fes")
                .rating(4.8)
                .reviewsCount(25)
                .isTeacher(true)
                .isVerified(true)
                .isPremium(true)
                .build();

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .firstName("Karim")
                .lastName("Najar")
                .status(AccountStatus.ACTIVE)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .artisan(artisan)
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(meCapabilitiesContributor.contribute(eq(user), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        ProfileResponse response = profileService.getCurrentUser();

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO profile = (ArtisanResponseDTO) response;
        assertThat(profile.getEmail()).isEqualTo("artisan@example.com");
        assertThat(profile.getBio()).isEqualTo("Master woodworker");
        assertThat(profile.getCity()).isEqualTo("Fes");
        assertThat(profile.isTeacher()).isTrue();
        assertThat(profile.isVerified()).isTrue();
        assertThat(profile.isPremium()).isTrue();
        assertThat(profile.getRating()).isZero();
    }

    /**
     * Verifies getCurrentUser returns ClientProfileResponseDTO for authenticated client.
     */
    @Test
    @DisplayName("getCurrentUser: returns ClientProfileResponseDTO for authenticated client")
    void getCurrentUser_withAuthenticatedClient_returnsClientProfileResponse() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        Client client = Client.builder()
                .companyName("Atlas Trade")
                .clientType(ClientType.BUSINESS.value())
                .city("Casablanca")
                .build();

        User user = User.builder()
                .email("client@example.com")
                .accountRole(AccountRole.CLIENT)
                .firstName("Sara")
                .lastName("Mansour")
                .status(AccountStatus.ACTIVE)
                .permissions(new HashSet<>(Set.of(clientRole)))
                .client(client)
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(meCapabilitiesContributor.contribute(eq(user), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        ProfileResponse response = profileService.getCurrentUser();

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ClientProfileResponseDTO profile = (ClientProfileResponseDTO) response;
        assertThat(profile.getEmail()).isEqualTo("client@example.com");
        assertThat(profile.getCompanyName()).isEqualTo("Atlas Trade");
        assertThat(profile.getClientType()).isEqualTo(ClientType.BUSINESS);
    }

    /**
     * Verifies completeProfile throws UnauthorizedException when unauthenticated.
     */
    @Test
    @DisplayName("completeProfile: throws UnauthorizedException when unauthenticated")
    void completeProfile_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();
        CompleteProfileRequestDTO dto = new CompleteProfileRequestDTO();

        assertThatThrownBy(() -> profileService.completeProfile(dto))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated.");
    }

    /**
     * Verifies completeProfile throws ResourceNotFoundException when user is not found.
     */
    @Test
    @DisplayName("completeProfile: throws ResourceNotFoundException when user not found")
    void completeProfile_whenUserNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("missing@example.com", "cred", List.of())
        );
        CompleteProfileRequestDTO dto = new CompleteProfileRequestDTO();

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.completeProfile(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: missing@example.com");
    }

    /**
     * Verifies completeProfile updates existing artisan entity without overwriting isTeacher.
     */
    @Test
    @DisplayName("completeProfile: updates existing artisan fields while preserving isTeacher")
    void completeProfile_forExistingArtisan_updatesAllFieldsAndReturnsProfile() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-1");

        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .isTeacher(false)
                .build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .bio("Updated bio")
                .regionId("REG-10")
                .city("Marrakech")
                .address("Souk Semmarine")
                .website("https://example.com/artisan")
                .subCategoryId("SUBCAT-1")
                .build();

        Region mockRegion = Region.builder().name("Marrakech").build();
        mockRegion.setId("REG-10");
        JobSubCategory mockSubCategory = JobSubCategory.builder().name("Pottery").build();
        mockSubCategory.setId("SUBCAT-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-1")).thenReturn(Optional.of(existingArtisan));
        when(regionRepository.findById("REG-10")).thenReturn(Optional.of(mockRegion));
        when(jobSubCategoryRepository.findById("SUBCAT-1")).thenReturn(Optional.of(mockSubCategory));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getBio()).isEqualTo("Updated bio");
        assertThat(existingArtisan.getRegionId()).isEqualTo("REG-10");
        assertThat(existingArtisan.getCity()).isEqualTo("Marrakech");
        assertThat(existingArtisan.getAddress()).isEqualTo("Souk Semmarine");
        assertThat(existingArtisan.getWebsite()).isEqualTo("https://example.com/artisan");
        assertThat(existingArtisan.getSubCategoryId()).isEqualTo("SUBCAT-1");
        assertThat(existingArtisan.isTeacher()).isFalse();
    }

    /**
     * Verifies completeProfile creates new artisan profile when none exists and resolves region from fallback.
     */
    @Test
    @DisplayName("completeProfile: creates new artisan profile with fallback region resolution")
    void completeProfile_forNewArtisan_createsProfileAndReturnsProfile() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-2");

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .region("REG-FALLBACK")
                .city("Rabat")
                .build();

        Region fallbackRegion = Region.builder().name("Rabat").build();
        fallbackRegion.setId("REG-FALLBACK");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-2")).thenReturn(Optional.empty());
        when(regionRepository.findById("REG-FALLBACK")).thenReturn(Optional.of(fallbackRegion));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArgumentCaptor<Artisan> captor = ArgumentCaptor.forClass(Artisan.class);
        verify(artisanRepository).save(captor.capture());
        Artisan created = captor.getValue();
        assertThat(created.getRegionId()).isEqualTo("REG-FALLBACK");
        assertThat(created.getCity()).isEqualTo("Rabat");
    }

    /**
     * Verifies completeProfile throws ResourceNotFoundException when region does not exist.
     */
    @Test
    @DisplayName("completeProfile: throws ResourceNotFoundException when region not found")
    void completeProfile_forArtisan_whenRegionNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-bad-region");

        Artisan artisan = Artisan.builder().user(user).build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .regionId("NON-EXISTENT-REGION")
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-bad-region")).thenReturn(Optional.of(artisan));
        when(regionRepository.findById("NON-EXISTENT-REGION")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.completeProfile(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Region not found: NON-EXISTENT-REGION");
    }

    /**
     * Verifies completeProfile throws ResourceNotFoundException when subcategory does not exist.
     */
    @Test
    @DisplayName("completeProfile: throws ResourceNotFoundException when subcategory not found")
    void completeProfile_forArtisan_whenSubCategoryNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-bad-subcat");

        Artisan artisan = Artisan.builder().user(user).build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .subCategoryId("NON-EXISTENT-SUBCAT")
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-bad-subcat")).thenReturn(Optional.of(artisan));
        when(jobSubCategoryRepository.findById("NON-EXISTENT-SUBCAT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.completeProfile(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("SubCategory not found: NON-EXISTENT-SUBCAT");
    }

    /**
     * Verifies completeProfile links materials, techniques, and epoques to artisan profile.
     */
    @Test
    @DisplayName("completeProfile: sets materials, techniques, and epoques on artisan")
    void completeProfile_forArtisan_withMaterialsTechniquesEpoques_success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-taxonomy");

        Artisan artisan = Artisan.builder().user(user).build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .materialIds(List.of("mat-1", "mat-2"))
                .techniqueIds(List.of("tech-1"))
                .epoqueIds(List.of("epo-1"))
                .build();

        Material m1 = Material.builder().name("Clay").slug("clay").build();
        m1.setId("mat-1");
        Material m2 = Material.builder().name("Copper").slug("copper").build();
        m2.setId("mat-2");

        Technique t1 = Technique.builder().name("Engraving").slug("engraving").build();
        t1.setId("tech-1");

        Epoque e1 = Epoque.builder().name("Ottoman").slug("ottoman").build();
        e1.setId("epo-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-taxonomy")).thenReturn(Optional.of(artisan));
        when(materialRepository.findAllById(List.of("mat-1", "mat-2"))).thenReturn(List.of(m1, m2));
        when(techniqueRepository.findAllById(List.of("tech-1"))).thenReturn(List.of(t1));
        when(epoqueRepository.findAllById(List.of("epo-1"))).thenReturn(List.of(e1));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        ArtisanResponseDTO artisanResp = (ArtisanResponseDTO) response;
        assertThat(artisanResp.getMaterials()).hasSize(2);
        assertThat(artisanResp.getTechniques()).hasSize(1);
        assertThat(artisanResp.getEpoques()).hasSize(1);
        verify(artisanRepository).save(artisan);
        assertThat(artisan.getMaterials()).containsExactlyInAnyOrder(m1, m2);
        assertThat(artisan.getTechniques()).containsExactly(t1);
        assertThat(artisan.getEpoques()).containsExactly(e1);
    }

    /**
     * Verifies completeProfile throws ResourceNotFoundException when one or more materials not found.
     */
    @Test
    @DisplayName("completeProfile: throws ResourceNotFoundException when material not found")
    void completeProfile_forArtisan_whenMaterialNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-mat-404");

        Artisan artisan = Artisan.builder().user(user).build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .materialIds(List.of("mat-1", "mat-invalid"))
                .build();

        Material m1 = Material.builder().name("Clay").build();
        m1.setId("mat-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-mat-404")).thenReturn(Optional.of(artisan));
        when(materialRepository.findAllById(List.of("mat-1", "mat-invalid"))).thenReturn(List.of(m1));

        assertThatThrownBy(() -> profileService.completeProfile(dto))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("One or more materials not found");
    }

    /**
     * Verifies completeProfile updates existing client entity.
     */
    @Test
    @DisplayName("completeProfile: updates existing client profile fields")
    void completeProfile_forExistingClient_updatesAllFieldsAndReturnsProfile() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-1");

        Client existingClient = Client.builder()
                .user(user)
                .build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .clientType(ClientType.BUSINESS)
                .companyName("Modern Souk")
                .bio("Retail distributor")
                .address("Boulevard Zerktouni")
                .regionId("REG-CASA")
                .city("Casablanca")
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-1")).thenReturn(Optional.of(existingClient));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getClientType()).isEqualTo("BUSINESS");
        assertThat(existingClient.getCompanyName()).isEqualTo("Modern Souk");
        assertThat(existingClient.getBio()).isEqualTo("Retail distributor");
        assertThat(existingClient.getAddress()).isEqualTo("Boulevard Zerktouni");
        assertThat(existingClient.getRegionId()).isEqualTo("REG-CASA");
        assertThat(existingClient.getCity()).isEqualTo("Casablanca");
    }

    /**
     * Verifies completeProfile creates new client profile when none exists and resolves region from fallback.
     */
    @Test
    @DisplayName("completeProfile: creates new client profile with fallback region resolution")
    void completeProfile_forNewClient_createsProfileAndReturnsProfile() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-2");

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder()
                .region("REG-FALLBACK-CLIENT")
                .city("Tangier")
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-2")).thenReturn(Optional.empty());

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(captor.capture());
        Client created = captor.getValue();
        assertThat(created.getRegionId()).isEqualTo("REG-FALLBACK-CLIENT");
        assertThat(created.getCity()).isEqualTo("Tangier");
    }

    /**
     * Verifies completeProfile returns unmodified profile when user is neither artisan nor client.
     */
    @Test
    @DisplayName("completeProfile: returns unmodified profile when user is neither artisan nor client")
    void completeProfile_whenNeitherArtisanNorClient_returnsProfileWithoutSaving() {
        AuthorizationPermission adminRole = new AuthorizationPermission();
        adminRole.setPermissionKey(Permission.Admin.USERS.value());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", "cred",
                        List.of(Permission.Admin.USERS))
        );

        User user = User.builder()
                .email("admin@example.com")
                .accountRole(AccountRole.ADMIN)
                .permissions(new HashSet<>(Set.of(adminRole)))
                .build();
        user.setId("admin-id");

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> profileService.completeProfile(new CompleteProfileRequestDTO()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Administrators do not possess an editable artisan or client profile.");
        verifyNoInteractions(artisanRepository);
        verifyNoInteractions(clientRepository);
    }

    /**
     * Verifies completeProfile preserves existing artisan regionId and city when DTO fields are null.
     */
    @Test
    @DisplayName("completeProfile: preserves existing artisan regionId and city when optional DTO fields are null")
    void completeProfile_forArtisan_withNullOptionalFields_preservesExistingValues() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-preserve-id");

        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .regionId("PRESERVED-REG")
                .city("PRESERVED-CITY")
                .build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder().build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-preserve-id")).thenReturn(Optional.of(existingArtisan));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getRegionId()).isEqualTo("PRESERVED-REG");
        assertThat(existingArtisan.getCity()).isEqualTo("PRESERVED-CITY");
    }

    /**
     * Verifies completeProfile preserves existing client regionId and city when DTO fields are null.
     */
    @Test
    @DisplayName("completeProfile: preserves existing client regionId and city when optional DTO fields are null")
    void completeProfile_forClient_withNullOptionalFields_preservesExistingValues() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-preserve-id");

        Client existingClient = Client.builder()
                .user(user)
                .regionId("PRESERVED-CLIENT-REG")
                .city("PRESERVED-CLIENT-CITY")
                .build();

        CompleteProfileRequestDTO dto = CompleteProfileRequestDTO.builder().build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-preserve-id")).thenReturn(Optional.of(existingClient));

        ProfileResponse response = profileService.completeProfile(dto);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getRegionId()).isEqualTo("PRESERVED-CLIENT-REG");
        assertThat(existingClient.getCity()).isEqualTo("PRESERVED-CLIENT-CITY");
    }

    /**
     * Verifies patchCurrentUser throws UnauthorizedException when unauthenticated.
     */
    @Test
    @DisplayName("patchCurrentUser: throws UnauthorizedException when unauthenticated")
    void patchCurrentUser_whenUnauthenticated_throwsUnauthorizedException() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> profileService.patchCurrentUser(new UserPatchDTO()))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Not authenticated.");
    }

    /**
     * Verifies patchCurrentUser throws ResourceNotFoundException when authenticated user not in DB.
     */
    @Test
    @DisplayName("patchCurrentUser: throws ResourceNotFoundException when authenticated user not in DB")
    void patchCurrentUser_whenUserNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("missing@example.com", "cred", List.of())
        );

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.patchCurrentUser(new UserPatchDTO()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: missing@example.com");
    }

    /**
     * Verifies patchCurrentUser throws ForbiddenException for admin users.
     */
    @Test
    @DisplayName("patchCurrentUser: throws ForbiddenException when user is an administrator")
    void patchCurrentUser_whenUserIsAdmin_throwsForbiddenException() {
        AuthorizationPermission adminRole = new AuthorizationPermission();
        adminRole.setPermissionKey(Permission.Admin.USERS.value());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", "cred",
                        List.of(Permission.Admin.USERS))
        );

        User user = User.builder()
                .email("admin@example.com")
                .accountRole(AccountRole.ADMIN)
                .permissions(new HashSet<>(Set.of(adminRole)))
                .build();

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> profileService.patchCurrentUser(new UserPatchDTO()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Administrators do not possess an editable artisan or client profile.");
    }

    /**
     * Verifies patchCurrentUser returns existing profile without modifications when payload is null.
     */
    @Test
    @DisplayName("patchCurrentUser: returns profile without modification when payload is null")
    void patchCurrentUser_whenPayloadIsNull_returnsProfileWithoutModifications() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));

        ProfileResponse response = profileService.patchCurrentUser(null);

        assertThat(response).isNotNull();
        verifyNoInteractions(clientRepository);
    }

    /**
     * Verifies patchCurrentUser returns existing profile when patch DTO is empty.
     */
    @Test
    @DisplayName("patchCurrentUser: returns profile without modification when patch DTO is empty")
    void patchCurrentUser_whenPayloadIsNullNode_returnsProfileWithoutModifications() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));

        ProfileResponse response = profileService.patchCurrentUser(new UserPatchDTO());

        assertThat(response).isNotNull();
        verifyNoInteractions(clientRepository);
    }

    /**
     * Verifies patchCurrentUser returns existing profile when artisan patch DTO is empty.
     */
    @Test
    @DisplayName("patchCurrentUser: returns profile without modification when payload is empty")
    void patchCurrentUser_whenPayloadIsEmpty_returnsProfileWithoutModifications() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));

        ProfileResponse response = profileService.patchCurrentUser(new UserPatchDTO());

        assertThat(response).isNotNull();
        verifyNoInteractions(clientRepository);
    }

    /**
     * Verifies patchCurrentUser leaves artisan fields unchanged when fields are undefined.
     */
    @Test
    @DisplayName("patchCurrentUser: leaves artisan fields unchanged when fields are undefined")
    void patchCurrentUser_forArtisan_whenFieldsUndefined_leavesFieldsUnchanged() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-undef");

        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .bio("Keep this bio")
                .city("Keep this city")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .city(PatchField.of("New City"))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-undef")).thenReturn(Optional.of(existingArtisan));

        ProfileResponse response = profileService.patchCurrentUser(patchDTO);

        assertThat(response).isNotNull();
        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getBio()).isEqualTo("Keep this bio");
        assertThat(existingArtisan.getCity()).isEqualTo("New City");
    }

    /**
     * Verifies patchCurrentUser clears artisan collections when empty lists are provided.
     */
    @Test
    @DisplayName("patchCurrentUser: clears artisan collections when empty lists are provided")
    void patchCurrentUser_forArtisan_whenEmptyCollections_clearsCollections() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-empty-tax");

        Material m1 = Material.builder().name("Wood").slug("wood").build();
        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .materials(new HashSet<>(Set.of(m1)))
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .materialIds(PatchField.of(Collections.emptyList()))
                .techniqueIds(PatchField.of(Collections.emptyList()))
                .epoqueIds(PatchField.of(Collections.emptyList()))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-empty-tax")).thenReturn(Optional.of(existingArtisan));

        profileService.patchCurrentUser(patchDTO);

        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getMaterials()).isEmpty();
        assertThat(existingArtisan.getTechniques()).isEmpty();
        assertThat(existingArtisan.getEpoques()).isEmpty();
    }

    /**
     * Verifies patchCurrentUser updates all valid non-null artisan fields.
     */
    @Test
    @DisplayName("patchCurrentUser: updates all valid artisan fields and saves")
    void patchCurrentUser_forArtisan_withValidFields_updatesFieldsAndSaves() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-patch");

        Artisan existingArtisan = Artisan.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of("New artisan bio"))
                .regionId(PatchField.of("REG-PATCH-1"))
                .city(PatchField.of("Fes"))
                .address(PatchField.of("Derb El Horra"))
                .website(PatchField.of("https://artisan.ma"))
                .subCategoryId(PatchField.of("SUBCAT-POTTERY"))
                .build();

        Region patchRegion = Region.builder().name("Fes").build();
        patchRegion.setId("REG-PATCH-1");
        JobSubCategory patchSubCat = JobSubCategory.builder().name("Pottery").build();
        patchSubCat.setId("SUBCAT-POTTERY");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-patch")).thenReturn(Optional.of(existingArtisan));
        when(regionRepository.findById("REG-PATCH-1")).thenReturn(Optional.of(patchRegion));
        when(jobSubCategoryRepository.findById("SUBCAT-POTTERY")).thenReturn(Optional.of(patchSubCat));

        ProfileResponse response = profileService.patchCurrentUser(patchDTO);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getBio()).isEqualTo("New artisan bio");
        assertThat(existingArtisan.getRegionId()).isEqualTo("REG-PATCH-1");
        assertThat(existingArtisan.getCity()).isEqualTo("Fes");
        assertThat(existingArtisan.getAddress()).isEqualTo("Derb El Horra");
        assertThat(existingArtisan.getWebsite()).isEqualTo("https://artisan.ma");
        assertThat(existingArtisan.getSubCategoryId()).isEqualTo("SUBCAT-POTTERY");
    }

    /**
     * Verifies patchCurrentUser clears artisan fields when explicit nulls are provided in payload.
     */
    @Test
    @DisplayName("patchCurrentUser: clears artisan fields when explicit nulls are submitted")
    void patchCurrentUser_forArtisan_withExplicitNullFields_clearsFieldsAndSaves() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-patch-null");

        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .bio("Old bio")
                .regionId("OLD-REG")
                .city("Old City")
                .address("Old Address")
                .website("https://old.ma")
                .subCategoryId("OLD-SUB")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of(null))
                .regionId(PatchField.of(null))
                .city(PatchField.of(null))
                .address(PatchField.of(null))
                .website(PatchField.of(null))
                .subCategoryId(PatchField.of(null))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-patch-null")).thenReturn(Optional.of(existingArtisan));

        profileService.patchCurrentUser(patchDTO);

        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getBio()).isNull();
        assertThat(existingArtisan.getRegionId()).isNull();
        assertThat(existingArtisan.getCity()).isNull();
        assertThat(existingArtisan.getAddress()).isNull();
        assertThat(existingArtisan.getWebsite()).isNull();
        assertThat(existingArtisan.getSubCategoryId()).isNull();
    }

    /**
     * Verifies patchCurrentUser updates artisan regionId when region key is provided instead of regionId.
     */
    @Test
    @DisplayName("patchCurrentUser: updates artisan regionId using region key fallback")
    void patchCurrentUser_forArtisan_withRegionFallback_updatesRegionId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-region-fallback");

        Artisan existingArtisan = Artisan.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .region(PatchField.of("REG-FALLBACK-ARTISAN"))
                .build();

        Region fallbackRegion = Region.builder().name("Fallback Region").build();
        fallbackRegion.setId("REG-FALLBACK-ARTISAN");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-region-fallback")).thenReturn(Optional.of(existingArtisan));
        when(regionRepository.findById("REG-FALLBACK-ARTISAN")).thenReturn(Optional.of(fallbackRegion));

        profileService.patchCurrentUser(patchDTO);

        assertThat(existingArtisan.getRegionId()).isEqualTo("REG-FALLBACK-ARTISAN");
    }

    /**
     * Verifies patchCurrentUser clears artisan regionId when explicit null is passed under region key.
     */
    @Test
    @DisplayName("patchCurrentUser: clears artisan regionId when explicit null is passed in region fallback key")
    void patchCurrentUser_forArtisan_withExplicitNullRegion_clearsRegionId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-region-null");

        Artisan existingArtisan = Artisan.builder().user(user).regionId("OLD-REG").build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .region(PatchField.of(null))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-region-null")).thenReturn(Optional.of(existingArtisan));

        profileService.patchCurrentUser(patchDTO);

        assertThat(existingArtisan.getRegionId()).isNull();
    }

    /**
     * Verifies patchCurrentUser updates materials, techniques, and epoques collections.
     */
    @Test
    @DisplayName("patchCurrentUser: updates materials, techniques, and epoques collections")
    void patchCurrentUser_forArtisan_withMaterialsTechniquesEpoques_updatesCollections() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-patch-tax");

        Artisan existingArtisan = Artisan.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .materialIds(PatchField.of(List.of("mat-1")))
                .techniqueIds(PatchField.of(List.of("tech-1")))
                .epoqueIds(PatchField.of(List.of("epo-1")))
                .build();

        Material m1 = Material.builder().name("Silver").slug("silver").build();
        m1.setId("mat-1");
        Technique t1 = Technique.builder().name("Filigree").slug("filigree").build();
        t1.setId("tech-1");
        Epoque e1 = Epoque.builder().name("Numidian").slug("numidian").build();
        e1.setId("epo-1");

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-patch-tax")).thenReturn(Optional.of(existingArtisan));
        when(materialRepository.findAllById(List.of("mat-1"))).thenReturn(List.of(m1));
        when(techniqueRepository.findAllById(List.of("tech-1"))).thenReturn(List.of(t1));
        when(epoqueRepository.findAllById(List.of("epo-1"))).thenReturn(List.of(e1));

        ProfileResponse response = profileService.patchCurrentUser(patchDTO);

        assertThat(response).isInstanceOf(ArtisanResponseDTO.class);
        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getMaterials()).containsExactly(m1);
        assertThat(existingArtisan.getTechniques()).containsExactly(t1);
        assertThat(existingArtisan.getEpoques()).containsExactly(e1);
    }

    /**
     * Verifies patchCurrentUser throws ResourceNotFoundException when material in patch payload does not exist.
     */
    @Test
    @DisplayName("patchCurrentUser: throws ResourceNotFoundException when material not found")
    void patchCurrentUser_forArtisan_whenMaterialNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-id-patch-mat404");

        Artisan existingArtisan = Artisan.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .materialIds(PatchField.of(List.of("mat-404")))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-id-patch-mat404")).thenReturn(Optional.of(existingArtisan));
        when(materialRepository.findAllById(List.of("mat-404"))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> profileService.patchCurrentUser(patchDTO))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("One or more materials not found");
    }

    @Test
    @DisplayName("patchCurrentUser: rejects missing techniques and epoques")
    void patchCurrentUser_forArtisan_whenTechniqueOrEpoqueNotFound_throwsResourceNotFoundException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT)));
        User user = User.builder().email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole))).build();
        user.setId("artisan-id-patch-taxonomy-404");
        Artisan artisan = Artisan.builder().user(user).build();
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById(user.getId())).thenReturn(Optional.of(artisan));

        when(techniqueRepository.findAllById(List.of("tech-404"))).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> profileService.patchCurrentUser(UserPatchDTO.builder()
                .techniqueIds(PatchField.of(List.of("tech-404"))).build()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("One or more techniques not found");

        when(epoqueRepository.findAllById(List.of("epo-404"))).thenReturn(Collections.emptyList());
        assertThatThrownBy(() -> profileService.patchCurrentUser(UserPatchDTO.builder()
                .epoqueIds(PatchField.of(List.of("epo-404"))).build()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("One or more epoques not found");
    }

    /**
     * Verifies patchCurrentUser leaves client fields unchanged when fields are undefined.
     */
    @Test
    @DisplayName("patchCurrentUser: leaves client fields unchanged when fields are undefined")
    void patchCurrentUser_forClient_whenFieldsUndefined_leavesFieldsUnchanged() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-undef");

        Client existingClient = Client.builder()
                .user(user)
                .bio("Original client bio")
                .companyName("Original Corp")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .city(PatchField.of("New Client City"))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-undef")).thenReturn(Optional.of(existingClient));

        ProfileResponse response = profileService.patchCurrentUser(patchDTO);

        assertThat(response).isNotNull();
        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getBio()).isEqualTo("Original client bio");
        assertThat(existingClient.getCity()).isEqualTo("New Client City");
        assertThat(existingClient.getCompanyName()).isEqualTo("Original Corp");
    }

    /**
     * Verifies patchCurrentUser updates only specified client fields and leaves others unchanged.
     */
    @Test
    @DisplayName("patchCurrentUser: updates only specified client fields and leaves others unchanged")
    void patchCurrentUser_forClient_whenSpecificFieldProvided_updatesOnlySpecifiedField() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-specific");

        Client existingClient = Client.builder()
                .user(user)
                .bio("Existing bio")
                .companyName("Old Corp")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .companyName(PatchField.of("Brand New Corp"))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-specific")).thenReturn(Optional.of(existingClient));

        profileService.patchCurrentUser(patchDTO);

        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getBio()).isEqualTo("Existing bio");
        assertThat(existingClient.getCompanyName()).isEqualTo("Brand New Corp");
    }

    /**
     * Verifies patchCurrentUser updates all valid non-null client fields.
     */
    @Test
    @DisplayName("patchCurrentUser: updates all valid client fields and saves")
    void patchCurrentUser_forClient_withValidFields_updatesFieldsAndSaves() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-patch");

        Client existingClient = Client.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of("Corporate buyer bio"))
                .address(PatchField.of("123 Commercial Ave"))
                .regionId(PatchField.of("REG-CLIENT-1"))
                .city(PatchField.of("Rabat"))
                .companyName(PatchField.of("Artisanal Exports"))
                .clientType(PatchField.of(ClientType.ENTERPRISE))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-patch")).thenReturn(Optional.of(existingClient));

        ProfileResponse response = profileService.patchCurrentUser(patchDTO);

        assertThat(response).isInstanceOf(ClientProfileResponseDTO.class);
        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getBio()).isEqualTo("Corporate buyer bio");
        assertThat(existingClient.getAddress()).isEqualTo("123 Commercial Ave");
        assertThat(existingClient.getRegionId()).isEqualTo("REG-CLIENT-1");
        assertThat(existingClient.getCity()).isEqualTo("Rabat");
        assertThat(existingClient.getCompanyName()).isEqualTo("Artisanal Exports");
        assertThat(existingClient.getClientType()).isEqualTo("ENTERPRISE");
    }

    /**
     * Verifies patchCurrentUser clears client fields when explicit nulls are provided in payload.
     */
    @Test
    @DisplayName("patchCurrentUser: clears client fields when explicit nulls are submitted")
    void patchCurrentUser_forClient_withExplicitNullFields_clearsFieldsAndSaves() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-patch-null");

        Client existingClient = Client.builder()
                .user(user)
                .bio("Old bio")
                .address("Old address")
                .regionId("OLD-REG")
                .city("Old city")
                .companyName("Old Corp")
                .clientType(ClientType.BUSINESS.value())
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of(null))
                .address(PatchField.of(null))
                .regionId(PatchField.of(null))
                .city(PatchField.of(null))
                .companyName(PatchField.of(null))
                .clientType(PatchField.of(null))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-patch-null")).thenReturn(Optional.of(existingClient));

        profileService.patchCurrentUser(patchDTO);

        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getBio()).isNull();
        assertThat(existingClient.getAddress()).isNull();
        assertThat(existingClient.getRegionId()).isNull();
        assertThat(existingClient.getCity()).isNull();
        assertThat(existingClient.getCompanyName()).isNull();
        assertThat(existingClient.getClientType()).isNull();
    }

    /**
     * Verifies patchCurrentUser updates client regionId when region key is provided instead of regionId.
     */
    @Test
    @DisplayName("patchCurrentUser: updates client regionId using region key fallback")
    void patchCurrentUser_forClient_withRegionFallback_updatesRegionId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-region-fallback");

        Client existingClient = Client.builder().user(user).build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .region(PatchField.of("REG-FALLBACK-CLIENT"))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-region-fallback")).thenReturn(Optional.of(existingClient));

        profileService.patchCurrentUser(patchDTO);

        assertThat(existingClient.getRegionId()).isEqualTo("REG-FALLBACK-CLIENT");
    }

    /**
     * Verifies patchCurrentUser clears client regionId when explicit null is passed under region key.
     */
    @Test
    @DisplayName("patchCurrentUser: clears client regionId when explicit null is passed in region fallback key")
    void patchCurrentUser_forClient_withExplicitNullRegion_clearsRegionId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-id-region-null");

        Client existingClient = Client.builder().user(user).regionId("OLD-REG").build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .region(PatchField.of(null))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-id-region-null")).thenReturn(Optional.of(existingClient));

        profileService.patchCurrentUser(patchDTO);

        assertThat(existingClient.getRegionId()).isNull();
    }

    /**
     * Verifies patchCurrentUser leaves artisan region unchanged when payload contains no region keys.
     */
    @Test
    @DisplayName("patchCurrentUser: leaves artisan region unchanged when payload contains no region keys")
    void patchCurrentUser_forArtisan_withoutRegionFields_leavesRegionUnchanged() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT))
        );

        User user = User.builder()
                .email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole)))
                .build();
        user.setId("artisan-no-reg-patch");

        Artisan existingArtisan = Artisan.builder()
                .user(user)
                .regionId("ORIGINAL-REG")
                .bio("Original bio")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of("Updated bio only"))
                .build();

        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-no-reg-patch")).thenReturn(Optional.of(existingArtisan));

        profileService.patchCurrentUser(patchDTO);

        verify(artisanRepository).save(existingArtisan);
        assertThat(existingArtisan.getBio()).isEqualTo("Updated bio only");
        assertThat(existingArtisan.getRegionId()).isEqualTo("ORIGINAL-REG");
    }

    /**
     * Verifies patchCurrentUser leaves client region unchanged when payload contains no region keys.
     */
    @Test
    @DisplayName("patchCurrentUser: leaves client region unchanged when payload contains no region keys")
    void patchCurrentUser_forClient_withoutRegionFields_leavesRegionUnchanged() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@example.com", "cred",
                        List.of(Permission.Profile.READ))
        );

        User user = User.builder()
                .email("client@example.com")
                .permissions(new HashSet<>(Set.of(clientRole)))
                .build();
        user.setId("client-no-reg-patch");

        Client existingClient = Client.builder()
                .user(user)
                .regionId("ORIGINAL-CLIENT-REG")
                .bio("Original client bio")
                .build();

        UserPatchDTO patchDTO = UserPatchDTO.builder()
                .bio(PatchField.of("Updated client bio only"))
                .build();

        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        when(clientRepository.findById("client-no-reg-patch")).thenReturn(Optional.of(existingClient));

        profileService.patchCurrentUser(patchDTO);

        verify(clientRepository).save(existingClient);
        assertThat(existingClient.getBio()).isEqualTo("Updated client bio only");
        assertThat(existingClient.getRegionId()).isEqualTo("ORIGINAL-CLIENT-REG");
    }

    @Test
    void completeAndPatchArtisanHandleEmptyAndBlankOptionalValues() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("artisan@example.com", "cred",
                        List.of(Permission.Artisan.CONTENT)));
        User user = User.builder().email("artisan@example.com")
                .accountRole(AccountRole.ARTISAN)
                .permissions(new HashSet<>(Set.of(artisanRole))).build();
        user.setId("artisan-edge");
        Artisan artisan = Artisan.builder().user(user).build();
        when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        when(artisanRepository.findById("artisan-edge")).thenReturn(Optional.of(artisan));
        CompleteProfileRequestDTO complete = CompleteProfileRequestDTO.builder()
                .materialIds(Collections.emptyList())
                .techniqueIds(Collections.emptyList())
                .epoqueIds(Collections.emptyList())
                .build();
        profileService.completeProfile(complete);
        verify(artisanRepository).save(artisan);

        UserPatchDTO patch = UserPatchDTO.builder()
                .regionId(PatchField.of(" "))
                .subCategoryId(PatchField.of(" "))
                .materialIds(PatchField.of(null))
                .techniqueIds(PatchField.of(null))
                .epoqueIds(PatchField.of(null))
                .build();
        profileService.patchCurrentUser(patch);
        assertThat(artisan.getRegion()).isNull();
        assertThat(artisan.getSubCategory()).isNull();
        assertThat(artisan.getMaterials()).isEmpty();
        assertThat(artisan.getTechniques()).isEmpty();
        assertThat(artisan.getEpoques()).isEmpty();
    }
}
