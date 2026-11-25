package com.project.souklab.service.artisan;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;
import com.project.souklab.dao.ArtisanProfileViewRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanProfileView;
import com.project.souklab.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Dedicated component for recording artisan profile views in an isolated transaction.
 * Uses Propagation.REQUIRES_NEW so that duplicate-view DataIntegrityViolationExceptions
 * do not mark the caller's read transaction as rollback-only.
 * Avoids mutating the caller's managed Artisan entity to prevent Hibernate dirty-checking
 * from overwriting atomic views_count increments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtisanProfileViewRecorder {

    private final ArtisanProfileViewRepository artisanProfileViewRepository;
    private final ArtisanRepository artisanRepository;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) {
        this.activityEventService = value;
    }

    /**
     * Records a profile view in an isolated transaction if eligible.
     * Skips view recording if viewer is viewing their own profile, is an admin,
     * or has already viewed this profile.
     *
     * @param viewer  the viewer requesting the profile
     * @param artisan the target artisan whose profile was requested
     * @param isSelf  true if viewer is the artisan
     * @param isAdmin true if viewer is an admin
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProfileViewIfEligible(User viewer, Artisan artisan, boolean isSelf, boolean isAdmin) {
        if (isSelf || isAdmin) {
            return;
        }

        if (artisanProfileViewRepository.existsByViewerIdAndArtisanId(viewer.getId(), artisan.getId())) {
            return;
        }

        try {
            ArtisanProfileView view = ArtisanProfileView.builder()
                    .viewer(viewer)
                    .artisan(artisan)
                    .build();
            artisanProfileViewRepository.saveAndFlush(view);
            artisanRepository.incrementViewsCount(artisan.getId());
            if (activityEventService != null) {
                activityEventService.record(AnalyticsEvent.Profile.VIEW, viewer.getId(), artisan.getId(),
                        Map.of(AnalyticsMetadata.Account.TYPE, viewer.getAccountRole()));
            }
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent duplicate profile view ignored for viewer {} and artisan {}",
                    viewer.getId(), artisan.getId());
        }
    }
}
