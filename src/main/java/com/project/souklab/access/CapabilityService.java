package com.project.souklab.access;

import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.ClientSubscription;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** The canonical computation of account role, live premium state, and authorization capabilities. */
@Service
public class CapabilityService {
    public static final Duration PREMIUM_GRACE = Duration.ofDays(3);

    private final ClientSubscriptionRepository clientSubscriptions;
    private final ArtisanSubscriptionRepository artisanSubscriptions;
    private final List<CapabilityRule> rules;

    @Autowired
    public CapabilityService(ClientSubscriptionRepository clientSubscriptions,
                             ArtisanSubscriptionRepository artisanSubscriptions,
                             List<CapabilityRule> rules) {
        this.clientSubscriptions = clientSubscriptions;
        this.artisanSubscriptions = artisanSubscriptions;
        this.rules = List.copyOf(rules);
    }

    /** Returns the empty, implicit context for unauthenticated visitors. */
    public ViewerContext visitor() {
        return new ViewerContext(null, null, PremiumStatus.none(), Set.of(), false);
    }

    @Transactional(readOnly = true)
    public ViewerContext compute(User user, Instant now) {
        if (user == null || now == null) {
            throw new IllegalArgumentException("User and evaluation time are required");
        }
        AccountRole role = resolveRole(user);
        ClientSubscription clientSubscription = role == AccountRole.CLIENT && user.getId() != null
                ? clientSubscriptions.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(user.getId(),
                        List.of(SubscriptionStatus.ACTIVE)).orElse(null)
                : null;
        ArtisanSubscription artisanSubscription = role == AccountRole.ARTISAN && user.getId() != null
                ? artisanSubscriptions.findFirstByAccountIdAndStatusInOrderByCreatedAtDesc(user.getId(),
                        List.of(SubscriptionStatus.ACTIVE)).orElse(null)
                : null;
        return compute(user, now, role, clientSubscription, artisanSubscription);
    }

    @Transactional(readOnly = true)
    public Map<String, ViewerContext> computeAll(Collection<User> users, Instant now) {
        if (users == null || now == null) {
            throw new IllegalArgumentException("Users and evaluation time are required");
        }
        List<User> accounts = users.stream().filter(Objects::nonNull).toList();
        Map<String, AccountRole> roles = new LinkedHashMap<>();
        accounts.stream().filter(user -> user.getId() != null)
                .forEach(user -> roles.put(user.getId(), resolveRole(user)));
        List<String> clientIds = roles.entrySet().stream().filter(entry -> entry.getValue() == AccountRole.CLIENT)
                .map(Map.Entry::getKey).toList();
        List<String> artisanIds = roles.entrySet().stream().filter(entry -> entry.getValue() == AccountRole.ARTISAN)
                .map(Map.Entry::getKey).toList();
        Map<String, ClientSubscription> clientByAccount = new LinkedHashMap<>();
        if (!clientIds.isEmpty()) {
            clientSubscriptions.findByAccountIdInAndStatusInOrderByCreatedAtDesc(clientIds,
                            List.of(SubscriptionStatus.ACTIVE))
                    .forEach(subscription -> clientByAccount.putIfAbsent(subscription.getAccount().getId(), subscription));
        }
        Map<String, ArtisanSubscription> artisanByAccount = new LinkedHashMap<>();
        if (!artisanIds.isEmpty()) {
            artisanSubscriptions.findByAccountIdInAndStatusInOrderByCreatedAtDesc(artisanIds,
                            List.of(SubscriptionStatus.ACTIVE))
                    .forEach(subscription -> artisanByAccount.putIfAbsent(subscription.getAccount().getId(), subscription));
        }
        Map<String, ViewerContext> result = new LinkedHashMap<>();
        for (User user : accounts) {
            AccountRole role = user.getId() == null ? resolveRole(user) : roles.get(user.getId());
            ViewerContext context = compute(user, now, role,
                    user.getId() == null ? null : clientByAccount.get(user.getId()),
                    user.getId() == null ? null : artisanByAccount.get(user.getId()));
            if (user.getId() != null) result.put(user.getId(), context);
        }
        return Map.copyOf(result);
    }

    private ViewerContext compute(User user, Instant now, AccountRole role,
                                  ClientSubscription clientSubscription, ArtisanSubscription artisanSubscription) {
        PremiumStatus premium = premiumStatus(role, now, clientSubscription, artisanSubscription);
        boolean formateur = role == AccountRole.ARTISAN && user.getArtisan() != null && user.getArtisan().isTeacher();
        EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
        CapabilityRuleContext context = new CapabilityRuleContext(user, role, premium, formateur);
        rules.forEach(rule -> rule.apply(context, capabilities));
        return new ViewerContext(user.getId(), role, premium, capabilities, formateur);
    }

    private AccountRole resolveRole(User user) {
        if (user.getAccountRole() != null) {
            return user.getAccountRole();
        }
        boolean admin = user.getPermissions() != null && user.getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> Permission.Admin.USERS.matches(value.getPermissionKey()));
        if (admin) return AccountRole.ADMIN;
        boolean artisan = user.getPermissions() != null && user.getPermissions().stream()
                .filter(value -> value != null && value.isEnabled())
                .anyMatch(value -> Permission.Artisan.CONTENT.matches(value.getPermissionKey()));
        return artisan ? AccountRole.ARTISAN : AccountRole.CLIENT;
    }

    private PremiumStatus premiumStatus(AccountRole role, Instant now,
                                        ClientSubscription clientSubscription,
                                        ArtisanSubscription artisanSubscription) {
        if (role != AccountRole.CLIENT && role != AccountRole.ARTISAN) {
            return PremiumStatus.none();
        }
        LocalDateTime expiry = role == AccountRole.CLIENT
                ? (clientSubscription == null ? null : clientSubscription.getExpiresAt())
                : (artisanSubscription == null ? null : artisanSubscription.getExpiresAt());
        Instant expiresAt = toInstant(expiry);
        if (expiresAt == null) {
            return PremiumStatus.none();
        }
        Instant graceEndsAt = expiresAt.plus(PREMIUM_GRACE);
        if (!now.isBefore(graceEndsAt)) {
            return new PremiumStatus(false, expiresAt, false, graceEndsAt, false);
        }
        return new PremiumStatus(true, expiresAt, !now.isBefore(expiresAt), graceEndsAt, false);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
