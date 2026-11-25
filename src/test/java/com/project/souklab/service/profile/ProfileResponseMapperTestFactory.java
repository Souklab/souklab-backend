package com.project.souklab.service.profile;

import com.project.souklab.access.CapabilityService;
import com.project.souklab.access.PremiumStatus;
import com.project.souklab.access.ViewerContext;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.User;

import java.time.Clock;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test mapper wired to a mocked capability source, keeping mapper tests independent of persistence. */
public final class ProfileResponseMapperTestFactory {
    private ProfileResponseMapperTestFactory() { }

    public static ProfileResponseMapper create() {
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.compute(any(User.class), any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            AccountRole role = user.getAccountRole() == null ? AccountRole.CLIENT : user.getAccountRole();
            boolean artisan = role == AccountRole.ARTISAN;
            boolean premium = artisan
                    ? user.getArtisan() != null && user.getArtisan().isPremium()
                    : role == AccountRole.CLIENT && user.getClient() != null && user.getClient().isPremium();
            boolean formateur = artisan && user.getArtisan() != null && user.getArtisan().isTeacher();
            return new ViewerContext(user.getId(), role,
                    new PremiumStatus(premium, null, false, null, false), Set.of(), formateur);
        });
        return new ProfileResponseMapper(capabilities, Clock.systemUTC());
    }
}
