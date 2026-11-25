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
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.dto.profile.UserPatchDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientType;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/**
 * Application service responsible for profile lifecycle management and taxonomy wiring.
 * Handles {@code GET /me}, {@code PATCH /me}, and {@code POST /complete-profile} for
 * both artisan and client account types.
 * <p>
 * Authentication concerns (login, tokens, OAuth2) remain in {@code AuthService}.
 * Pure mapping logic lives in {@link ProfileResponseMapper}.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final String ERROR_USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String ERROR_NOT_AUTHENTICATED = "Not authenticated.";

    private final UserRepository userRepository;
    private final ArtisanRepository artisanRepository;
    private final ClientRepository clientRepository;
    private final RegionRepository regionRepository;
    private final JobSubCategoryRepository jobSubCategoryRepository;
    private final MaterialRepository materialRepository;
    private final TechniqueRepository techniqueRepository;
    private final EpoqueRepository epoqueRepository;
    private final ProfileResponseMapper profileResponseMapper;
    private final MeCapabilitiesContributor meCapabilitiesContributor;

    /**
     * Returns the profile of the currently authenticated user.
     *
     * @return an account-type-specific {@link ProfileResponse} for the authenticated user
     * @throws UnauthorizedException     if no user is currently authenticated
     * @throws ResourceNotFoundException if the authenticated email is not present in the database
     */
    @Transactional(readOnly = true)
    public ProfileResponse getCurrentUser() {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new UnauthorizedException(ERROR_NOT_AUTHENTICATED);
        }

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + email));

        return meCapabilitiesContributor.contribute(user, profileResponseMapper.mapToProfileResponse(user));
    }

    /**
     * Completes profile creation for an Artisan or Client.
     * For artisans, resolves and sets region, sub-category, materials, techniques, and epoques.
     * For clients, sets company name, bio, address, region, and city.
     *
     * @param dto the profile completion payload
     * @return the updated account-type-specific profile response
     * @throws UnauthorizedException     if no user is authenticated
     * @throws ResourceNotFoundException if the authenticated email does not map to an existing user,
     *                                   or if any referenced taxonomy entity does not exist
     */
    @Transactional
    public ProfileResponse completeProfile(CompleteProfileRequestDTO dto) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new UnauthorizedException(ERROR_NOT_AUTHENTICATED);
        }

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + email));

        validateEditableProfile(user);

        if (isArtisan(user)) {
            completeArtisanProfile(user, dto);
        } else {
            completeClientProfile(user, dto);
        }

        return profileResponseMapper.mapToProfileResponse(user);
    }

    /**
     * Partially updates (PATCH) the authenticated user's profile based on their account type using a
     * strongly-typed {@link UserPatchDTO}.
     * Follows "omitted = unchanged, explicit null = clear" semantics modelled by {@link PatchField}.
     *
     * @param dto the strongly-typed patch payload; may be {@code null} to no-op
     * @return the updated account-type-specific profile response
     * @throws UnauthorizedException     if no user is authenticated
     * @throws ResourceNotFoundException if the authenticated email does not map to an existing user
     * @throws ForbiddenException        if the authenticated user is an administrator (no editable profile)
     */
    @Transactional
    public ProfileResponse patchCurrentUser(UserPatchDTO dto) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new UnauthorizedException(ERROR_NOT_AUTHENTICATED);
        }

        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + email));

        validateEditableProfile(user);

        if (dto == null || dto.isEmpty()) {
            return profileResponseMapper.mapToProfileResponse(user);
        }

        if (dto.getFirstName() != null && dto.getFirstName().isDefined()) {
            user.setFirstName(dto.getFirstName().getValue());
        }
        if (dto.getLastName() != null && dto.getLastName().isDefined()) {
            user.setLastName(dto.getLastName().getValue());
        }
        if (dto.getPhone() != null && dto.getPhone().isDefined()) {
            user.setPhone(dto.getPhone().getValue());
        }
        userRepository.save(user);

        if (isArtisan(user)) {
            patchArtisanProfile(user, dto);
        } else {
            patchClientProfile(user, dto);
        }

        return profileResponseMapper.mapToProfileResponse(user);
    }

    private void validateEditableProfile(User user) {
        if (isAdmin(user)) {
            throw new ForbiddenException("Administrators do not possess an editable artisan or client profile.");
        }
    }

    private boolean isAdmin(User user) {
        return user != null && user.getAccountRole() == AccountRole.ADMIN;
    }

    private boolean isArtisan(User user) {
        return user != null && user.getAccountRole() == AccountRole.ARTISAN;
    }

    /**
     * Completes the artisan profile for the given user using the supplied DTO.
     * Resolves the region, sub-category, materials, techniques, and epoques by ID,
     * then persists the artisan entity and updates the user's artisan reference.
     *
     * @param user the user whose artisan profile should be completed or initialised
     * @param dto  the profile completion payload
     * @throws ResourceNotFoundException if any referenced taxonomy entity is not found
     */
    private void completeArtisanProfile(User user, CompleteProfileRequestDTO dto) {
        Artisan profile = artisanRepository.findById(user.getId())
                .orElse(Artisan.builder().user(user).build());

        if (dto.getBio() != null) profile.setBio(dto.getBio());
        if (dto.resolveRegionId() != null) {
            profile.setRegion(resolveRegion(dto.resolveRegionId()));
        }
        if (dto.getCity() != null) profile.setCity(dto.getCity());
        if (dto.getAddress() != null) profile.setAddress(dto.getAddress());
        if (dto.getWebsite() != null) profile.setWebsite(dto.getWebsite());
        if (dto.getSubCategoryId() != null) {
            profile.setSubCategory(resolveSubCategory(dto.getSubCategoryId()));
        }
        if (dto.getMaterialIds() != null && !dto.getMaterialIds().isEmpty()) {
            profile.setMaterials(new HashSet<>(resolveMaterials(dto.getMaterialIds())));
        }
        if (dto.getTechniqueIds() != null && !dto.getTechniqueIds().isEmpty()) {
            profile.setTechniques(new HashSet<>(resolveTechniques(dto.getTechniqueIds())));
        }
        if (dto.getEpoqueIds() != null && !dto.getEpoqueIds().isEmpty()) {
            profile.setEpoques(new HashSet<>(resolveEpoques(dto.getEpoqueIds())));
        }

        artisanRepository.save(profile);
        user.setArtisan(profile);
    }

    /**
     * Completes the client profile for the given user using the supplied DTO.
     * Sets client-specific scalar fields and persists the client entity,
     * then updates the user's client reference.
     *
     * @param user the user whose client profile should be completed or initialised
     * @param dto  the profile completion payload
     */
    private void completeClientProfile(User user, CompleteProfileRequestDTO dto) {
        Client client = clientRepository.findById(user.getId())
                .orElse(Client.builder().user(user).build());

        if (dto.getClientType() != null) client.setClientType(dto.getClientType().value());
        if (dto.getCompanyName() != null) client.setCompanyName(dto.getCompanyName());
        if (dto.getBio() != null) client.setBio(dto.getBio());
        if (dto.getAddress() != null) client.setAddress(dto.getAddress());
        if (dto.resolveRegionId() != null) client.setRegionId(dto.resolveRegionId());
        if (dto.getCity() != null) client.setCity(dto.getCity());

        clientRepository.save(client);
        user.setClient(client);
    }

    /**
     * Applies a strongly-typed patch payload to the artisan profile of the given user.
     *
     * @param user the user whose artisan profile should be updated
     * @param dto  the strongly-typed patch payload
     */
    private void patchArtisanProfile(User user, UserPatchDTO dto) {
        Artisan artisan = artisanRepository.findById(user.getId())
                .orElse(Artisan.builder().user(user).build());

        applyArtisanScalarPatch(artisan, dto);
        applyArtisanTaxonomyPatch(artisan, dto);

        artisanRepository.save(artisan);
        user.setArtisan(artisan);
    }

    /**
     * Applies scalar (single-valued) field patches to the given {@link Artisan} entity.
     * Only fields defined in the patch DTO are updated; undefined fields are left unchanged.
     * Explicit {@code null} values clear the corresponding field.
     *
     * @param artisan the artisan entity to mutate
     * @param dto     the strongly-typed patch DTO holding typed field values
     * @throws ResourceNotFoundException if the referenced region or sub-category does not exist
     */
    private void applyArtisanScalarPatch(Artisan artisan, UserPatchDTO dto) {
        if (dto.getBio().isDefined()) {
            artisan.setBio(dto.getBio().getValue());
        }
        PatchField<String> regionField = dto.resolveRegionField();
        if (regionField.isDefined()) {
            String regionId = regionField.getValue();
            artisan.setRegion((regionId != null && !regionId.isBlank()) ? resolveRegion(regionId) : null);
        }
        if (dto.getCity().isDefined()) {
            artisan.setCity(dto.getCity().getValue());
        }
        if (dto.getAddress().isDefined()) {
            artisan.setAddress(dto.getAddress().getValue());
        }
        if (dto.getWebsite().isDefined()) {
            artisan.setWebsite(dto.getWebsite().getValue());
        }
        if (dto.getSubCategoryId().isDefined()) {
            String subCatId = dto.getSubCategoryId().getValue();
            artisan.setSubCategory((subCatId != null && !subCatId.isBlank()) ? resolveSubCategory(subCatId) : null);
        }
    }

    /**
     * Applies collection (many-valued) taxonomy patches to the given {@link Artisan} entity.
     * An explicit {@code null} or empty list clears the collection; a non-empty list replaces
     * the collection wholesale.
     *
     * @param artisan the artisan entity to mutate
     * @param dto     the strongly-typed patch DTO holding typed field values
     * @throws ResourceNotFoundException if any referenced material, technique, or epoque does not exist
     */
    private void applyArtisanTaxonomyPatch(Artisan artisan, UserPatchDTO dto) {
        if (dto.getMaterialIds().isDefined()) {
            List<String> matIds = dto.getMaterialIds().getValue();
            artisan.setMaterials(matIds == null || matIds.isEmpty()
                    ? new HashSet<>()
                    : new HashSet<>(resolveMaterials(matIds)));
        }
        if (dto.getTechniqueIds().isDefined()) {
            List<String> techIds = dto.getTechniqueIds().getValue();
            artisan.setTechniques(techIds == null || techIds.isEmpty()
                    ? new HashSet<>()
                    : new HashSet<>(resolveTechniques(techIds)));
        }
        if (dto.getEpoqueIds().isDefined()) {
            List<String> epIds = dto.getEpoqueIds().getValue();
            artisan.setEpoques(epIds == null || epIds.isEmpty()
                    ? new HashSet<>()
                    : new HashSet<>(resolveEpoques(epIds)));
        }
    }

    /**
     * Applies a strongly-typed patch payload to the client profile of the given user.
     *
     * @param user the user whose client profile should be updated
     * @param dto  the strongly-typed patch payload
     */
    private void patchClientProfile(User user, UserPatchDTO dto) {
        Client client = clientRepository.findById(user.getId())
                .orElse(Client.builder().user(user).build());

        if (dto.getBio().isDefined()) {
            client.setBio(dto.getBio().getValue());
        }
        if (dto.getAddress().isDefined()) {
            client.setAddress(dto.getAddress().getValue());
        }
        PatchField<String> regionField = dto.resolveRegionField();
        if (regionField.isDefined()) {
            client.setRegionId(regionField.getValue());
        }
        if (dto.getCity().isDefined()) {
            client.setCity(dto.getCity().getValue());
        }
        if (dto.getCompanyName().isDefined()) {
            client.setCompanyName(dto.getCompanyName().getValue());
        }
        if (dto.getClientType().isDefined()) {
            ClientType clientType = dto.getClientType().getValue();
            client.setClientType(clientType == null ? null : clientType.value());
        }

        clientRepository.save(client);
        user.setClient(client);
    }

    /**
     * Resolves a {@link Region} entity by its identifier.
     *
     * @param regionId the identifier of the region to load
     * @return the resolved {@link Region}
     * @throws ResourceNotFoundException if no region with the given ID exists
     */
    private Region resolveRegion(String regionId) {
        return regionRepository.findById(regionId)
                .orElseThrow(() -> new ResourceNotFoundException("Region not found: " + regionId));
    }

    /**
     * Resolves a {@link JobSubCategory} entity by its identifier.
     *
     * @param subCategoryId the identifier of the sub-category to load
     * @return the resolved {@link JobSubCategory}
     * @throws ResourceNotFoundException if no sub-category with the given ID exists
     */
    private JobSubCategory resolveSubCategory(String subCategoryId) {
        return jobSubCategoryRepository.findById(subCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("SubCategory not found: " + subCategoryId));
    }

    /**
     * Resolves a batch of {@link Material} entities by their identifiers.
     * Throws if any ID is missing.
     *
     * @param ids the list of material identifiers to load
     * @return the fully resolved list of {@link Material} entities
     * @throws ResourceNotFoundException if the number of found materials does not match the number of requested IDs
     */
    private List<Material> resolveMaterials(List<String> ids) {
        List<Material> materials = materialRepository.findAllById(ids);
        if (materials.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more materials not found");
        }
        return materials;
    }

    /**
     * Resolves a batch of {@link Technique} entities by their identifiers.
     * Throws if any ID is missing.
     *
     * @param ids the list of technique identifiers to load
     * @return the fully resolved list of {@link Technique} entities
     * @throws ResourceNotFoundException if the number of found techniques does not match the number of requested IDs
     */
    private List<Technique> resolveTechniques(List<String> ids) {
        List<Technique> techniques = techniqueRepository.findAllById(ids);
        if (techniques.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more techniques not found");
        }
        return techniques;
    }

    /**
     * Resolves a batch of {@link Epoque} entities by their identifiers.
     * Throws if any ID is missing.
     *
     * @param ids the list of epoque identifiers to load
     * @return the fully resolved list of {@link Epoque} entities
     * @throws ResourceNotFoundException if the number of found epoques does not match the number of requested IDs
     */
    private List<Epoque> resolveEpoques(List<String> ids) {
        List<Epoque> epoques = epoqueRepository.findAllById(ids);
        if (epoques.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more epoques not found");
        }
        return epoques;
    }
}
