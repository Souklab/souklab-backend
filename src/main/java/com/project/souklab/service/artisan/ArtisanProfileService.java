package com.project.souklab.service.artisan;

import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.Capability;
import com.project.souklab.dao.ArtisanCertificationRepository;
import com.project.souklab.dao.ArtisanGalleryImageRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
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
import com.project.souklab.exception.UnauthorizedException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanCertification;
import com.project.souklab.model.User;
import com.project.souklab.security.ViewerPremiumResolver;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service managing artisan profile retrieval, contact privacy gating,
 * view counts tracking, and taxonomy and portfolio assembly.
 */
@Service
@RequiredArgsConstructor
public class ArtisanProfileService {

    private static final String ERROR_NOT_AUTHENTICATED = "Not authenticated.";
    private static final String ERROR_USER_NOT_FOUND_PREFIX = "User not found: ";
    private static final String ERROR_ARTISAN_NOT_FOUND_PREFIX = "Artisan not found with id: ";

    private final UserRepository userRepository;
    private final ArtisanRepository artisanRepository;
    private final ArtisanGalleryImageRepository artisanGalleryImageRepository;
    private final ArtisanCertificationRepository artisanCertificationRepository;
    private final ViewerPremiumResolver viewerPremiumResolver;
    private final ArtisanProfileViewRecorder artisanProfileViewRecorder;
    private final CapabilityService capabilityService;
    private final Clock clock;

    /**
     * Retrieves an artisan's profile for authenticated viewers.
     * Applies account status and email verification gating on the viewer (bypassed for admins),
     * records deduplicated profile views, gates sensitive contact info based on viewer premium status,
     * and compiles craft taxonomy, showcase images, and professional certifications.
     *
     * @param artisanId the ID of the target artisan to view
     * @return ArtisanPublicViewDTO containing the public/gated artisan profile
     */
    @Transactional(readOnly = true)
    public ArtisanPublicViewDTO getArtisanProfile(String artisanId) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new UnauthorizedException(ERROR_NOT_AUTHENTICATED);
        }

        User viewer = userRepository.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_USER_NOT_FOUND_PREFIX + email));

        boolean isAdmin = capabilityService.compute(viewer, Instant.now(clock)).can(Capability.MANAGE_USERS);

        verifyViewerAccess(viewer, isAdmin);

        Artisan artisan = artisanRepository.findById(artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        boolean isSelf = viewer.getId().equals(artisan.getId());

        artisanProfileViewRecorder.recordProfileViewIfEligible(viewer, artisan, isSelf, isAdmin);

        boolean contactInfoLocked = resolveContactInfoLocked(viewer, isSelf, isAdmin);

        User targetUser = artisan.getUser();
        String name = resolveName(artisan, targetUser, contactInfoLocked);
        String phone = null;
        String contactEmail = null;
        String website = null;
        String address = null;
        if (!contactInfoLocked) {
            website = artisan.getWebsite();
            address = artisan.getAddress();
            if (targetUser != null) {
                phone = targetUser.getPhone();
                contactEmail = targetUser.getEmail();
            }
        }

        RegionSummaryDTO regionSummary = RegionSummaryDTO.from(artisan.getRegion());
        JobSubCategorySummaryDTO subCategorySummary = JobSubCategorySummaryDTO.from(artisan.getSubCategory());

        Set<MaterialSummaryDTO> materialSummaries = artisan.getMaterials() != null
                ? artisan.getMaterials().stream().map(MaterialSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        Set<TechniqueSummaryDTO> techniqueSummaries = artisan.getTechniques() != null
                ? artisan.getTechniques().stream().map(TechniqueSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        Set<EpoqueSummaryDTO> epoqueSummaries = artisan.getEpoques() != null
                ? artisan.getEpoques().stream().map(EpoqueSummaryDTO::from).collect(Collectors.toSet())
                : Collections.emptySet();

        List<GalleryImageResponseDTO> galleryImageDTOs = artisanGalleryImageRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(artisan.getId())
                .stream()
                .map(GalleryImageResponseDTO::from)
                .toList();

        List<CertificationResponseDTO> certificationDTOs = artisanCertificationRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc(artisan.getId())
                .stream()
                .map(cert -> mapCertification(cert, contactInfoLocked))
                .toList();

        return ArtisanPublicViewDTO.builder()
                .id(artisan.getId())
                .bio(artisan.getBio())
                .city(artisan.getCity())
                .regionId(artisan.getRegionId())
                .region(regionSummary)
                .subCategoryId(artisan.getSubCategoryId())
                .subCategory(subCategorySummary)
                .materials(materialSummaries)
                .techniques(techniqueSummaries)
                .epoques(epoqueSummaries)
                .galleryImages(galleryImageDTOs)
                .certifications(certificationDTOs)
                .rating(0.0)
                .reviewsCount(0)
                .teacher(targetUser != null && capabilityService.compute(targetUser, Instant.now(clock)).formateur())
                .verified(artisan.isVerified())
                .avatarUrl(targetUser != null ? targetUser.getAvatarUrl() : null)
                .createdAt(artisan.getCreatedAt())
                .contactInfoLocked(contactInfoLocked)
                .name(name)
                .phone(phone)
                .email(contactEmail)
                .website(website)
                .address(address)
                .build();
    }

    /**
     * Verifies the viewer is allowed to browse public artisan profiles.
     * Non-admin viewers must hold an ACTIVE account with a verified email address.
     *
     * @param viewer  the authenticated user performing the request
     * @param isAdmin {@code true} if the viewer holds the administrator permission
     * @throws ForbiddenException if the non-admin viewer's account or email is not verified
     */
    private void verifyViewerAccess(User viewer, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (viewer.getStatus() != AccountStatus.ACTIVE) {
            throw new ForbiddenException("Your account is not active.");
        }
        if (!viewer.isEmailVerified()) {
            throw new ForbiddenException("Please verify your email address to access artisan profiles.");
        }
    }

    /**
     * Resolves whether contact information should be hidden from the viewer.
     * Owners and admins always see contact info. Third-party viewers must hold a premium subscription.
     *
     * @param viewer  the authenticated user performing the request
     * @param isSelf  {@code true} if the viewer owns the profile
     * @param isAdmin {@code true} if the viewer holds the administrator permission
     * @return {@code true} if contact fields must be masked; {@code false} otherwise
     */
    private boolean resolveContactInfoLocked(User viewer, boolean isSelf, boolean isAdmin) {
        return viewerPremiumResolver.isContactInfoLocked(viewer, isSelf, isAdmin);
    }

    /**
     * Resolves the display name for the artisan profile.
     * When contact info is locked, returns an anonymised identifier; otherwise resolves the real name.
     *
     * @param artisan           the target artisan
     * @param targetUser        the user entity linked to the artisan
     * @param contactInfoLocked {@code true} if contact fields are masked
     * @return the display name to expose
     */
    private String resolveName(Artisan artisan, User targetUser, boolean contactInfoLocked) {
        if (contactInfoLocked) {
            return ViewerPremiumResolver.maskArtisanName(artisan != null ? artisan.getId() : null);
        }
        if (targetUser == null) {
            return null;
        }
        String resolvedName = targetUser.getName();
        if ((resolvedName == null || resolvedName.isBlank())
                && (targetUser.getFirstName() != null || targetUser.getLastName() != null)) {
            String first = targetUser.getFirstName() != null ? targetUser.getFirstName() : "";
            String last = targetUser.getLastName() != null ? targetUser.getLastName() : "";
            resolvedName = (first + " " + last).trim();
        }
        return resolvedName;
    }

    /**
     * Maps a single certification entity to its response DTO, masking the document URL
     * when contact information is locked for the requesting viewer.
     *
     * @param cert              the certification entity to map
     * @param contactInfoLocked {@code true} if the document URL should be suppressed
     * @return the mapped response DTO
     */
    private CertificationResponseDTO mapCertification(ArtisanCertification cert, boolean contactInfoLocked) {
        if (contactInfoLocked) {
            return CertificationResponseDTO.builder()
                    .id(cert.getId())
                    .title(cert.getTitle())
                    .issuer(cert.getIssuer())
                    .issuedAt(cert.getIssuedAt())
                    .expiresAt(cert.getExpiresAt())
                    .isVerified(cert.isVerified())
                    .documentUrl(null)
                    .build();
        }
        return CertificationResponseDTO.from(cert);
    }
}
