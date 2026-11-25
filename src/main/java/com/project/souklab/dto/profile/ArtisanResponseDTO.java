package com.project.souklab.dto.profile;

import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.dto.catalog.EpoqueSummaryDTO;
import com.project.souklab.dto.catalog.JobSubCategorySummaryDTO;
import com.project.souklab.dto.catalog.MaterialSummaryDTO;
import com.project.souklab.dto.catalog.RegionSummaryDTO;
import com.project.souklab.dto.catalog.TechniqueSummaryDTO;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.security.Permission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import com.project.souklab.access.Capability;
import com.project.souklab.access.PremiumStatus;

/**
 * Profile response DTO scoped to ARTISAN users.
 * Includes artisan-specific fields (teacher, verified, premium, rating, taxonomy, showcase)
 * that are meaningless for client users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtisanResponseDTO implements ProfileResponse {

    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String name;
    private String phone;
    private String avatarUrl;
    private AccountStatus accountStatus;
    private AccountRole accountRole;
    private Set<Capability> capabilities;
    private PremiumStatus premiumStatus;
    private Set<Permission> permissions;
    private boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String bio;
    private String regionId;
    private RegionSummaryDTO region;
    private String city;
    private String address;
    private String website;
    private String subCategoryId;
    private JobSubCategorySummaryDTO subCategory;

    @Builder.Default
    private Set<MaterialSummaryDTO> materials = Collections.emptySet();

    @Builder.Default
    private Set<TechniqueSummaryDTO> techniques = Collections.emptySet();

    @Builder.Default
    private Set<EpoqueSummaryDTO> epoques = Collections.emptySet();

    @Builder.Default
    private List<GalleryImageResponseDTO> galleryImages = Collections.emptyList();

    @Builder.Default
    private List<CertificationResponseDTO> certifications = Collections.emptyList();

    /**
     * Whether the artisan has been granted Formateur (instructor) status.
     * Only set via the Formateur approve/grant/revoke flow — never by the artisan themselves.
     */
    private boolean teacher;

    /**
     * Whether the artisan's credentials have been admin-verified (artisans.is_verified).
     */
    private boolean verified;

    /**
     * Whether the artisan holds an active premium subscription.
     */
    private boolean premium;

    private double rating;
    private int reviewsCount;

    @Override
    public String getId() { return id; }

    @Override
    public String getEmail() { return email; }

    @Override
    public String getFirstName() { return firstName; }

    @Override
    public String getLastName() { return lastName; }

    @Override
    public String getName() { return name; }

    @Override
    public String getPhone() { return phone; }

    @Override
    public String getAvatarUrl() { return avatarUrl; }

    @Override
    public AccountStatus getAccountStatus() { return accountStatus; }

    @Override
    public Set<Permission> getPermissions() { return permissions; }

    @Override
    public boolean isEmailVerified() { return emailVerified; }

    @Override
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }

    @Override
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
