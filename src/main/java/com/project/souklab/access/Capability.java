package com.project.souklab.access;

/** Authorization capabilities derived from the persisted account role and live entitlements. */
public enum Capability {
    START_CONVERSATION,
    SEND_MESSAGE,
    VIEW_ARTISAN_IDENTITY,
    VIEW_FORMATIONS,
    ENROLL_FORMATIONS,
    PUBLISH_FORMATIONS,
    REQUEST_FORMATEUR,
    PUBLISH_POSTS,
    MANAGE_GALLERY,
    MANAGE_CERTIFICATIONS,
    MANAGE_FAVORITES,
    MANAGE_SUBSCRIPTION,
    HAS_MEMBER_PROFILE,
    HAS_PUBLIC_PROFILE,
    DELETE_ACCOUNT,
    ADMIN_USERS,
    ADMIN_AUDIT,
    ADMIN_FORMATIONS,
    ADMIN_FEED,
    ADMIN_REPORTS,
    ADMIN_CATALOG,
    ADMIN_FINANCE
}
