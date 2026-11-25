package com.project.souklab.access;

/** Authorization capabilities derived from the persisted account role and live entitlements. */
public enum Capability {
    START_CONVERSATION,
    SEND_MESSAGE,
    VIEW_ARTISAN_IDENTITY,
    VIEW_FORMATIONS,
    ENROLL_FORMATIONS,
    PUBLISH_FORMATIONS,
    MANAGE_FORMATIONS,
    MANAGE_FORMATEURS,
    MANAGE_USERS
}
