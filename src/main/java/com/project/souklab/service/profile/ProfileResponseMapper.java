package com.project.souklab.service.profile;

import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.dto.auth.UserSummaryDTO;
import com.project.souklab.dto.catalog.EpoqueSummaryDTO;
import com.project.souklab.dto.catalog.JobSubCategorySummaryDTO;
import com.project.souklab.dto.catalog.MaterialSummaryDTO;
import com.project.souklab.dto.catalog.RegionSummaryDTO;
import com.project.souklab.dto.catalog.TechniqueSummaryDTO;
import com.project.souklab.dto.profile.ArtisanResponseDTO;
import com.project.souklab.dto.profile.ClientProfileResponseDTO;
import com.project.souklab.dto.profile.ProfileResponse;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientType;
import com.project.souklab.model.User;
import com.project.souklab.model.AuthorizationPermission;
import com.project.souklab.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * Stateless mapping component that converts {@link User} entities into
 * account-type-specific {@link ProfileResponse} subtypes and admin-facing {@link UserSummaryDTO}.
 * No repository or service dependencies are injected here; all data is resolved
 * from the entity graph passed in by the caller.
 */
@Component
public class ProfileResponseMapper {


    /**
     * Dispatches to the correct account-type-specific profile DTO.
     * Artisans receive {@link ArtisanResponseDTO}; all others receive {@link ClientProfileResponseDTO}.
     *
     * @param user the user entity to map
     * @return the appropriate {@link ProfileResponse} subtype based on the user's permissions
     */
    public ProfileResponse mapToProfileResponse(User user) {
        boolean isArtisan = user.getPermissions().stream()
                .anyMatch(permission -> Permission.Artisan.CONTENT.matches(permission.getPermissionKey()));

        Set<Permission> permissionKeys = user.getPermissions().stream()
                .map(AuthorizationPermission::getPermissionKey)
                .map(Permission::fromValue)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());

        if (isArtisan) {
            return buildArtisanProfileResponse(user, permissionKeys);
        }

        return buildClientProfileResponse(user, permissionKeys);
    }

    /**
     * Kept for admin {@code UserManagementService} compatibility — returns the shared
     * {@link UserSummaryDTO} which is appropriate for admin views where all fields are
     * intentionally visible.
     *
     * @param user the user entity to summarise
     * @return a {@link UserSummaryDTO} populated with admin-visible fields
     */
    public UserSummaryDTO mapToSummaryDTO(User user) {
        boolean isTeacher = user.getArtisan() != null && user.getArtisan().isTeacher();
        boolean isPremium = (user.getArtisan() != null && user.getArtisan().isPremium())
                || (user.getClient() != null && user.getClient().isPremium());
        boolean isValidated = (user.getArtisan() != null && user.getArtisan().isVerified())
                || (user.getClient() != null && user.getClient().isVerified());

        return UserSummaryDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .name(user.getName())
                .permissions(canonicalPermissions(user))
                .accountStatus(user.getStatus())
                .isPremium(isPremium)
                .isValidated(isValidated)
                .isTeacher(isTeacher)
                .build();
    }

    /**
     * Builds the full {@link ArtisanResponseDTO} for a user with artisan capabilities,
     * assembling all taxonomy summaries, gallery images, and certifications from
     * the associated {@link Artisan} profile.
     *
     * @param user      the artisan user entity
     * @param permissionKeys the set of permission keys assigned to the user
     * @return the fully populated {@link ArtisanResponseDTO}
     */
    private ArtisanResponseDTO buildArtisanProfileResponse(User user, Set<Permission> permissionKeys) {
        Artisan profile = user.getArtisan();
        RegionSummaryDTO regionSummary = profile != null ? RegionSummaryDTO.from(profile.getRegion()) : null;
        JobSubCategorySummaryDTO subCategorySummary = profile != null ? JobSubCategorySummaryDTO.from(profile.getSubCategory()) : null;

        Set<MaterialSummaryDTO> materialSummaries = profile != null && profile.getMaterials() != null
                ? profile.getMaterials().stream().map(MaterialSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        Set<TechniqueSummaryDTO> techniqueSummaries = profile != null && profile.getTechniques() != null
                ? profile.getTechniques().stream().map(TechniqueSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        Set<EpoqueSummaryDTO> epoqueSummaries = profile != null && profile.getEpoques() != null
                ? profile.getEpoques().stream().map(EpoqueSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        List<GalleryImageResponseDTO> gallerySummaries = profile != null && profile.getGalleryImages() != null
                ? profile.getGalleryImages().stream()
                        .filter(img -> img.getDeletedAt() == null)
                        .sorted(Comparator.comparingInt(ArtisanGalleryImage::getDisplayOrder))
                        .map(GalleryImageResponseDTO::from)
                        .toList()
                : Collections.emptyList();

        List<CertificationResponseDTO> certSummaries = profile != null && profile.getCertifications() != null
                ? profile.getCertifications().stream()
                        .filter(cert -> cert.getDeletedAt() == null)
                        .map(CertificationResponseDTO::from)
                        .toList()
                : Collections.emptyList();

        return ArtisanResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .name(user.getName())
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .accountStatus(user.getStatus())
                .permissions(permissionKeys)
                .emailVerified(user.isEmailVerified())
                .emailVerifiedAt(user.getEmailVerifiedAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .bio(profile != null ? profile.getBio() : null)
                .regionId(profile != null ? profile.getRegionId() : null)
                .region(regionSummary)
                .city(profile != null ? profile.getCity() : null)
                .address(profile != null ? profile.getAddress() : null)
                .website(profile != null ? profile.getWebsite() : null)
                .subCategoryId(profile != null ? profile.getSubCategoryId() : null)
                .subCategory(subCategorySummary)
                .materials(materialSummaries)
                .techniques(techniqueSummaries)
                .epoques(epoqueSummaries)
                .galleryImages(gallerySummaries)
                .certifications(certSummaries)
                .teacher(profile != null && profile.isTeacher())
                .verified(profile != null && profile.isVerified())
                .premium(profile != null && profile.isPremium())
                .rating(0.0)
                .reviewsCount(0)
                .build();
    }

    /**
     * Builds the {@link ClientProfileResponseDTO} for a user without artisan capabilities,
     * populating only client-specific fields from the associated {@link Client} profile.
     *
     * @param user      the client user entity
     * @param permissionKeys the set of permission keys assigned to the user
     * @return the fully populated {@link ClientProfileResponseDTO}
     */
    private ClientProfileResponseDTO buildClientProfileResponse(User user, Set<Permission> permissionKeys) {
        Client client = user.getClient();
        return ClientProfileResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .name(user.getName())
                .phone(user.getPhone())
                .avatarUrl(user.getAvatarUrl())
                .accountStatus(user.getStatus())
                .permissions(permissionKeys)
                .emailVerified(user.isEmailVerified())
                .emailVerifiedAt(user.getEmailVerifiedAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .clientType(client != null ? ClientType.fromInput(client.getClientType()).orElse(ClientType.INDIVIDUAL) : ClientType.INDIVIDUAL)
                .companyName(client != null ? client.getCompanyName() : null)
                .bio(client != null ? client.getBio() : null)
                .address(client != null ? client.getAddress() : null)
                .regionId(client != null ? client.getRegionId() : null)
                .city(client != null ? client.getCity() : null)
                .build();
    }

    private Set<Permission> canonicalPermissions(User user) {
        return user.getPermissions().stream()
                .filter(AuthorizationPermission::isEnabled)
                .map(AuthorizationPermission::getPermissionKey)
                .map(Permission::fromValue)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
    }
}
