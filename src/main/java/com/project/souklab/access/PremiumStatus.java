package com.project.souklab.access;

import java.time.Instant;

public record PremiumStatus(boolean active, Instant expiresAt, boolean inGrace, Instant graceEndsAt,
                            boolean cancelAtPeriodEnd) {
    public static PremiumStatus none() {
        return new PremiumStatus(false, null, false, null, false);
    }
}
