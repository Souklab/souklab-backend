package com.project.souklab.analytics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.config.AnalyticsJobProperties;
import com.project.souklab.config.AnalyticsExportProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.config.OperationalMetrics;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dao.analytics.ActivityEventRepository;
import com.project.souklab.dao.analytics.AnalyticsDimensionCount;
import com.project.souklab.dao.analytics.AnalyticsJobRepository;
import com.project.souklab.dao.analytics.AnalyticsJobArtifactRepository;
import com.project.souklab.dao.analytics.AnalyticsOutboxRepository;
import com.project.souklab.dao.analytics.AnalyticsMaintenanceJobRepository;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.ArtisanReviewRepository;
import com.project.souklab.dao.ContentReportRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dto.analytics.AnalyticsJobRequest;
import com.project.souklab.dto.analytics.AnalyticsJobResponse;
import com.project.souklab.dto.analytics.AnalyticsResult;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.EnumValue;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.model.ReviewStatus;
import com.project.souklab.model.analytics.AnalyticsJob;
import com.project.souklab.model.analytics.AnalyticsJobStatus;
import com.project.souklab.model.analytics.AnalyticsAuditOutcome;
import com.project.souklab.model.analytics.AnalyticsJobArtifact;
import com.project.souklab.model.analytics.AnalyticsBucket;
import com.project.souklab.model.analytics.AnalyticsReportType;
import com.project.souklab.model.analytics.AnalyticsFilterKey;
import com.project.souklab.model.analytics.AnalyticsOutputFormat;
import com.project.souklab.model.analytics.OutboxStatus;
import com.project.souklab.dto.analytics.AnalyticsJobEvent;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.FormationStatus;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.ReportStatus;
import com.project.souklab.model.PaymentStatus;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.StorageResource;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.security.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.time.DayOfWeek;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsJobService {
    private final AnalyticsJobRepository jobs;
    private final ActivityEventRepository events;
    private final UserRepository users;
    private final AnalyticsProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SimpMessagingTemplate messagingTemplate;
    private final SubscriptionProperties subscriptionProperties;
    private final ChatProperties chatProperties;
    private final FeedPostRepository feedPosts;
    private final FormationRepository formations;
    private final FormationEnrollmentRepository enrollments;
    private final ArtisanReviewRepository reviews;
    private final ContentReportRepository reports;
    private final PaymentRepository payments;
    private final AnalyticsJobArtifactRepository artifacts;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final AnalyticsOutboxRepository outbox;
    private final AnalyticsJobProperties jobProperties;
    private final AnalyticsExportProperties exportProperties;
    private final ArtisanSubscriptionRepository artisanSubscriptions;
    private final ClientSubscriptionRepository clientSubscriptions;
    private final ArtisanFormateurRequestRepository formateurRequests;
    @Qualifier("applicationTaskExecutor")
    private final Executor applicationTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private OperationalMetrics operationalMetrics;
    private Semaphore concurrencyLimiter;
    private HealthEndpoint healthEndpoint;
    private AnalyticsMaintenanceJobRepository maintenanceJobs;

    @Autowired(required = false)
    void setHealthEndpoint(HealthEndpoint value) { this.healthEndpoint = value; }

    @Autowired(required = false)
    void setMaintenanceJobs(AnalyticsMaintenanceJobRepository value) { this.maintenanceJobs = value; }

    @Autowired(required = false)
    void setOperationalMetrics(OperationalMetrics value) { this.operationalMetrics = value; }

    @PostConstruct
    void initializeConcurrencyLimiter() {
        concurrencyLimiter = new Semaphore(jobProperties.getConcurrency());
    }

    @Transactional
    public AnalyticsJobResponse submit(AnalyticsJobRequest request, String username, boolean financial) {
        validate(request, financial);
        String ownerId = users.findByEmail(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated administrator not found")).getId();
        AnalyticsJob job = new AnalyticsJob();
        job.setOwnerId(ownerId);
        job.setReportType(request.getReportType());
        job.setBucket(request.getBucket());
        job.setFromDate(request.getFromDate()); job.setToDate(request.getToDate());
        job.setPageNumber(request.getPageNumber() == null ? 0 : request.getPageNumber());
        job.setPageSize(request.getPageSize() == null ? properties.getDefaultPageSize() : request.getPageSize());
        job.setSortField(request.getSortField()); job.setSortDirection(request.getSortDirection());
        job.setOutputFormat(request.getOutputFormat() == null ? AnalyticsOutputFormat.JSON : request.getOutputFormat());
        job.setStatus(AnalyticsJobStatus.QUEUED);
        job.setPermissionScope(financial
                ? Permission.Analytics.ADMIN.value() + "," + Permission.Financial.ADMIN.value()
                : Permission.Analytics.ADMIN.value());
        try { job.setFiltersJson(objectMapper.writeValueAsString(request.getFilters() == null ? Map.of() : request.getFilters())); }
        catch (JsonProcessingException e) { throw new BadRequestException("Invalid analytics filters", e); }
        job.setExpiresAt(LocalDateTime.now(clock).plus(properties.getJobRetention()));
        AnalyticsJob saved = jobs.saveAndFlush(job);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            String jobId = saved.getId();
            TransactionSynchronizationManager.registerSynchronization(new AnalyticsJobCommitCallback(jobId, this));
        } else {
            processAsync(saved.getId());
        }
        audit(AuditLogAction.Analytics.Job.SUBMITTED, saved, AnalyticsAuditOutcome.ACCEPTED);
        return response(saved);
    }

    public void processAsync(String id) {
        applicationTaskExecutor.execute(() -> transactionTemplate.executeWithoutResult(status -> processJob(id)));
    }

    private void processJob(String id) {
        concurrencyLimiter.acquireUninterruptibly();
        try {
            jobs.findById(id).ifPresent(job -> {
            try {
                long startedAt = System.nanoTime();
                job.setStatus(AnalyticsJobStatus.RUNNING);
                jobs.save(job);
                Map<AnalyticsMetric.Key, Object> summary = new LinkedHashMap<>();
                LocalDateTime from = utcStart(job.getFromDate());
                LocalDateTime to = utcStart(job.getToDate().plusDays(1));
                LocalDateTime inclusiveTo = to.minusNanos(1);
                summary.put(AnalyticsMetric.Summary.User.Count.TOTAL, users.countByDeletedAtIsNull());
                summary.put(AnalyticsMetric.Summary.User.Registration.NEW, users.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.User.Registration.VERIFIED, users.countByEmailVerifiedTrueAndCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                long registrations = (long) summary.get(AnalyticsMetric.Summary.User.Registration.NEW);
                long verifiedRegistrations = (long) summary.get(AnalyticsMetric.Summary.User.Registration.VERIFIED);
                summary.put(AnalyticsMetric.Summary.User.Activation.RATE, registrations == 0 ? 0.0 : (double) verifiedRegistrations / registrations);
                summary.put(AnalyticsMetric.Summary.User.Verification.USERS, users.countByEmailVerifiedTrueAndDeletedAtIsNull());
                summary.put(AnalyticsMetric.Summary.User.Profile.ARTISAN, users.countArtisanProfiles());
                summary.put(AnalyticsMetric.Summary.User.Profile.CLIENT, users.countClientProfiles());
                summary.put(AnalyticsMetric.Summary.User.Profile.Active.ARTISAN, users.countActiveArtisanProfiles(AccountStatus.ACTIVE));
                summary.put(AnalyticsMetric.Summary.User.Profile.Active.CLIENT, users.countActiveClientProfiles(AccountStatus.ACTIVE));
                summary.put(AnalyticsMetric.Summary.User.Status.ACTIVE, users.countByStatusAndDeletedAtIsNull(AccountStatus.ACTIVE));
                summary.put(AnalyticsMetric.Summary.User.Status.PENDING, users.countByStatusAndDeletedAtIsNull(AccountStatus.PENDING));
                summary.put(AnalyticsMetric.Summary.User.Status.SUSPENDED, users.countByStatusAndDeletedAtIsNull(AccountStatus.SUSPENDED));
                summary.put(AnalyticsMetric.Summary.User.Approval.PENDING, users.countByStatusAndDeletedAtIsNull(AccountStatus.PENDING));
                Map<AnalyticsEvent.Type, Long> moderationActivity = new LinkedHashMap<>();
                for (AnalyticsEvent.Type eventType : List.of(AnalyticsEvent.User.APPROVED, AnalyticsEvent.User.SUSPENDED,
                        AnalyticsEvent.User.Timeout.EVENT, AnalyticsEvent.User.REINSTATED,
                        AnalyticsEvent.Formation.Moderation.APPROVED, AnalyticsEvent.Formation.Moderation.REJECTED,
                        AnalyticsEvent.Report.RESOLVED)) {
                    moderationActivity.put(eventType, countFilteredEvent(job, eventType, from, inclusiveTo));
                }
                summary.put(AnalyticsMetric.Summary.Moderation.Activity.SUMMARY, moderationActivity);
                Map<AccountStatus, Long> userStatuses = new LinkedHashMap<>();
                for (AccountStatus status : AccountStatus.values()) {
                    userStatuses.put(status, users.countByStatusAndDeletedAtIsNull(status));
                }
                summary.put(AnalyticsMetric.Summary.User.Status.ALL, userStatuses);
                summary.put(AnalyticsMetric.Summary.Engagement.Activity.EVENTS, countFilteredEvents(job, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Login.SUCCESSFUL, countFilteredEvent(job, AnalyticsEvent.Authentication.Login.SUCCEEDED, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Post.PUBLISHED, countFilteredEvent(job, AnalyticsEvent.Feed.Post.PUBLISHED, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Message.SENT, countFilteredEvent(job, AnalyticsEvent.Message.SENT, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Profile.VIEWS, countFilteredEvent(job, AnalyticsEvent.Profile.VIEW, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Report.RESOLUTIONS, countFilteredEvent(job, AnalyticsEvent.Report.RESOLVED, from, inclusiveTo));
                LocalDateTime activityDayStart = utcStart(job.getToDate());
                LocalDateTime activityWeekStart = utcStart(job.getToDate().minusDays(6));
                LocalDateTime activityMonthStart = utcStart(job.getToDate().minusDays(29));
                summary.put(AnalyticsMetric.Summary.Engagement.Audience.DAU, countFilteredDistinctActors(job, activityDayStart, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Audience.WAU, countFilteredDistinctActors(job, activityWeekStart, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Engagement.Audience.MAU, countFilteredDistinctActors(job, activityMonthStart, inclusiveTo));
                AnalyticsEvent.Type eventFilter = eventTypeFilter(job);
                summary.put(AnalyticsMetric.Summary.Engagement.Dimension.Account.TYPE, Map.of(
                        AnalyticsMetric.AccountType.ARTISAN, eventFilter == null
                                ? events.countDistinctArtisanActorsByEventTimeBetween(from, inclusiveTo)
                                : events.countDistinctArtisanActorsByTypeAndEventTimeBetween(eventFilter, from, inclusiveTo),
                        AnalyticsMetric.AccountType.CLIENT, eventFilter == null
                                ? events.countDistinctClientActorsByEventTimeBetween(from, inclusiveTo)
                                : events.countDistinctClientActorsByTypeAndEventTimeBetween(eventFilter, from, inclusiveTo)));
                summary.put(AnalyticsMetric.Summary.Engagement.Dimension.Region.VALUE, dimensionCounts(
                        events.countDistinctActorsByRegionAndEventTimeBetween(eventFilter, from, inclusiveTo)));
                summary.put(AnalyticsMetric.Summary.Engagement.Dimension.Craft.CATEGORY, dimensionCounts(
                        events.countDistinctActorsByCraftCategoryAndEventTimeBetween(eventFilter, from, inclusiveTo)));
                if (job.getReportType() == AnalyticsReportType.Growth.REPORT) {
                    summary.put(AnalyticsMetric.Summary.Engagement.Retention.Login.COHORTS, loginRetentionCohorts(from, inclusiveTo));
                }
                if (feedPosts != null) summary.put(AnalyticsMetric.Summary.Content.Feed.CREATED, feedPosts.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                if (formations != null) {
                    summary.put(AnalyticsMetric.Summary.Formation.Count.CREATED, formations.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                    summary.put(AnalyticsMetric.Summary.Formation.Instructor.ACTIVE,
                            formations.countDistinctAuthorsByStatusAndDeletedAtIsNull(FormationStatus.PUBLISHED));
                }
                if (enrollments != null) summary.put(AnalyticsMetric.Summary.Formation.Count.ENROLLMENTS, enrollments.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                if (reviews != null) summary.put(AnalyticsMetric.Summary.Content.Review.SUBMITTED, reviews.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                if (reviews != null) {
                    summary.put(AnalyticsMetric.Summary.Content.Review.PUBLISHED, reviews.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                            ReviewStatus.PUBLISHED, from, inclusiveTo));
                    BigDecimal averageRating = reviews.averageRatingByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                            ReviewStatus.PUBLISHED, from, inclusiveTo);
                    summary.put(AnalyticsMetric.Summary.Content.Review.AVERAGE_RATING, averageRating == null ? BigDecimal.ZERO : averageRating);
                }
                if (reports != null) summary.put(AnalyticsMetric.Summary.Report.Submission.COUNT, reports.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                if (reports != null) {
                    Double averageResolutionSeconds = reports.averageResolutionSecondsByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo);
                    summary.put(AnalyticsMetric.Summary.Report.Resolution.Average.SECONDS,
                            averageResolutionSeconds == null ? 0.0 : averageResolutionSeconds);
                }
                summary.put(AnalyticsMetric.Summary.Moderation.Formateur.PENDING, formateurRequests.countByStatusAndDeletedAtIsNull(FormateurRequestStatus.PENDING));
                summary.put(AnalyticsMetric.Summary.Moderation.Formateur.Approved.InRange.VALUE, formateurRequests.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                        FormateurRequestStatus.APPROVED, from, inclusiveTo));
                summary.put(AnalyticsMetric.Summary.Moderation.Formateur.Rejected.InRange.VALUE, formateurRequests.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                        FormateurRequestStatus.REJECTED, from, inclusiveTo));
                Map<FormateurRequestStatus, Long> formateurStatuses = new LinkedHashMap<>();
                for (FormateurRequestStatus status : FormateurRequestStatus.values()) {
                    formateurStatuses.put(status, formateurRequests.countByStatusAndDeletedAtIsNull(status));
                }
                summary.put(AnalyticsMetric.Summary.Moderation.Formateur.Statuses.VALUE, formateurStatuses);
                if (payments != null) summary.put(AnalyticsMetric.Summary.Payment.Count.CREATED, payments.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo));
                if (feedPosts != null) {
                    Map<FeedPostStatus, Long> statuses = new LinkedHashMap<>();
                    for (FeedPostStatus status : FeedPostStatus.values()) statuses.put(status, feedPosts.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                    summary.put(AnalyticsMetric.Summary.Content.Feed.BY_STATUS, statuses);
                }
                if (formations != null) {
                    Map<FormationStatus, Long> statuses = new LinkedHashMap<>();
                    for (FormationStatus status : FormationStatus.values()) statuses.put(status, formations.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                    summary.put(AnalyticsMetric.Summary.Formation.Status.By.STATUS, statuses);
                }
                if (enrollments != null) {
                    Map<EnrollmentStatus, Long> statuses = new LinkedHashMap<>();
                    for (EnrollmentStatus status : EnrollmentStatus.values()) statuses.put(status, enrollments.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                    summary.put(AnalyticsMetric.Summary.Formation.Enrollment.By.STATUS, statuses);
                    long enrollmentTotal = statuses.values().stream().mapToLong(Long::longValue).sum();
                    summary.put(AnalyticsMetric.Summary.Formation.Count.COMPLETIONS, statuses.getOrDefault(EnrollmentStatus.ATTENDED, 0L));
                    summary.put(AnalyticsMetric.Summary.Formation.Enrollment.Cancellation.RATE, enrollmentTotal == 0 ? 0.0
                            : (double) statuses.getOrDefault(EnrollmentStatus.CANCELLED, 0L) / enrollmentTotal);
                    if (formations != null) {
                        long capacity = formations.sumMaxParticipantsByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                                FormationStatus.PUBLISHED, from, inclusiveTo);
                        long confirmed = enrollments.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(
                                EnrollmentStatus.CONFIRMED, from, inclusiveTo);
                        summary.put(AnalyticsMetric.Summary.Formation.Utilization.RATE,
                                capacity == 0 ? 0.0 : (double) confirmed / capacity);
                    }
                }
                if (reports != null) {
                    Map<ReportStatus, Long> statuses = new LinkedHashMap<>();
                    for (ReportStatus status : ReportStatus.values()) statuses.put(status, reports.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                    summary.put(AnalyticsMetric.Summary.Report.Status.By.STATUS, statuses);
                }
                if (payments != null) {
                    Map<PaymentStatus, Long> statuses = new LinkedHashMap<>();
                        for (PaymentStatus status : PaymentStatus.values()) {
                        statuses.put(status, payments.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                    }
                    summary.put(AnalyticsMetric.Summary.Payment.Status.By.STATUS, statuses);
                    if (job.getReportType() == AnalyticsReportType.Subscriptions.PAYMENTS) {
                        Map<SubscriptionStatus, Long> subscriptions = new LinkedHashMap<>();
                        for (SubscriptionStatus status : SubscriptionStatus.values()) {
                            subscriptions.put(status, artisanSubscriptions.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo)
                                    + clientSubscriptions.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                        }
                        summary.put(AnalyticsMetric.Summary.Subscription.Status.By.STATUS, subscriptions);
                        Map<AnalyticsMetric.AccountType, Map<?, Long>> subscriptionsBySubscriberType = new LinkedHashMap<>();
                        Map<SubscriptionStatus, Long> artisanSubscriptionStatuses = new LinkedHashMap<>();
                        Map<SubscriptionStatus, Long> clientSubscriptionStatuses = new LinkedHashMap<>();
                            for (SubscriptionStatus status : SubscriptionStatus.values()) {
                            artisanSubscriptionStatuses.put(status, artisanSubscriptions.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                            clientSubscriptionStatuses.put(status, clientSubscriptions.countByStatusAndCreatedAtBetweenAndDeletedAtIsNull(status, from, inclusiveTo));
                        }
                        subscriptionsBySubscriberType.put(AnalyticsMetric.AccountType.ARTISAN, artisanSubscriptionStatuses);
                        subscriptionsBySubscriberType.put(AnalyticsMetric.AccountType.CLIENT, clientSubscriptionStatuses);
                        summary.put(AnalyticsMetric.Summary.Subscription.Subscriber.TYPE, subscriptionsBySubscriberType);
                        Map<AnalyticsEvent.Type, Long> lifecycleEvents = new LinkedHashMap<>();
                        for (AnalyticsEvent.Type eventType : List.of(AnalyticsEvent.Subscription.ACTIVATED, AnalyticsEvent.Subscription.EXPIRED,
                                AnalyticsEvent.Subscription.CANCELED, AnalyticsEvent.Subscription.REVOKED, AnalyticsEvent.Subscription.RENEWAL)) {
                            lifecycleEvents.put(eventType, countFilteredEvent(job, eventType, from, inclusiveTo));
                        }
                        summary.put(AnalyticsMetric.Summary.Subscription.Lifecycle.EVENTS, lifecycleEvents);
                        summary.put(AnalyticsMetric.Summary.Payment.Checkout.CREATED, countFilteredEvent(job, AnalyticsEvent.Checkout.CREATED, from, inclusiveTo));
                        summary.put(AnalyticsMetric.Summary.Payment.State.TRANSITIONS, countFilteredEvent(job, AnalyticsEvent.Payment.State.TRANSITION, from, inclusiveTo));
                        String collectedCurrency = subscriptionProperties.getCurrency();
                        long grossCollected = payments.sumAmountByStatusAndCurrencyAndCreatedAtBetween(
                                PaymentStatus.PAID, collectedCurrency, from, to);
                        long providerFees = payments.sumFeesByStatusAndCurrencyAndCreatedAtBetween(
                                PaymentStatus.PAID, collectedCurrency, from, to);
                        summary.put(AnalyticsMetric.Summary.Payment.Revenue.Gross.Collected.DZD, grossCollected);
                        summary.put(AnalyticsMetric.Summary.Payment.Revenue.ProviderFees.DZD, providerFees);
                        summary.put(AnalyticsMetric.Summary.Payment.Revenue.Net.Collected.DZD, grossCollected - providerFees);
                        long paidPayments = payments.countByStatusAndManualGrantFalseAndCreatedAtBetweenAndDeletedAtIsNull(
                                PaymentStatus.PAID, from, inclusiveTo);
                        long failedPayments = payments.countByStatusAndManualGrantFalseAndCreatedAtBetweenAndDeletedAtIsNull(
                                PaymentStatus.FAILED, from, inclusiveTo)
                                + payments.countByStatusAndManualGrantFalseAndCreatedAtBetweenAndDeletedAtIsNull(
                                PaymentStatus.CANCELED, from, inclusiveTo)
                                + payments.countByStatusAndManualGrantFalseAndCreatedAtBetweenAndDeletedAtIsNull(
                                PaymentStatus.EXPIRED, from, inclusiveTo);
                        summary.put(AnalyticsMetric.Summary.Payment.Conversion.RATE, paidPayments + failedPayments == 0 ? 0.0
                                : (double) paidPayments / (paidPayments + failedPayments));
                        summary.put(AnalyticsMetric.Summary.Payment.Grant.MANUAL, payments.countByManualGrantTrueAndCreatedAtBetweenAndDeletedAtIsNull(
                                from, inclusiveTo));
                    }
                }
                if (job.getReportType() == AnalyticsReportType.Operational.REPORT) {
                    Map<AnalyticsMetric.Key, Object> operational = new LinkedHashMap<>();
                    operational.put(AnalyticsMetric.Operational.Job.Analytics.QUEUED, jobs.countByStatus(AnalyticsJobStatus.QUEUED));
                    operational.put(AnalyticsMetric.Operational.Job.Analytics.RUNNING, jobs.countByStatus(AnalyticsJobStatus.RUNNING));
                    operational.put(AnalyticsMetric.Operational.Job.Analytics.COMPLETED, jobs.countByStatus(AnalyticsJobStatus.COMPLETED));
                    operational.put(AnalyticsMetric.Operational.Job.Analytics.FAILED, jobs.countByStatus(AnalyticsJobStatus.FAILED));
                    if (maintenanceJobs != null) {
                        operational.put(AnalyticsMetric.Operational.Job.Maintenance.QUEUED, maintenanceJobs.countByStatus(AnalyticsJobStatus.QUEUED));
                        operational.put(AnalyticsMetric.Operational.Job.Maintenance.RUNNING, maintenanceJobs.countByStatus(AnalyticsJobStatus.RUNNING));
                        operational.put(AnalyticsMetric.Operational.Job.Maintenance.COMPLETED, maintenanceJobs.countByStatus(AnalyticsJobStatus.COMPLETED));
                        operational.put(AnalyticsMetric.Operational.Job.Maintenance.FAILED, maintenanceJobs.countByStatus(AnalyticsJobStatus.FAILED));
                    }
                    operational.put(AnalyticsMetric.Operational.Outbox.PENDING, outbox.countByStatus(OutboxStatus.PENDING));
                    operational.put(AnalyticsMetric.Operational.Outbox.PUBLISHED, outbox.countByStatus(OutboxStatus.PUBLISHED));
                    operational.put(AnalyticsMetric.Operational.Outbox.DEAD_LETTER, outbox.countByStatus(OutboxStatus.DEAD_LETTER));
                    if (healthEndpoint != null) {
                        HealthDescriptor health = healthEndpoint.health();
                        operational.put(AnalyticsMetric.Operational.Health.APPLICATION, health.getStatus().getCode());
                        operational.put(AnalyticsMetric.Operational.Health.COMPONENTS, healthComponentStatuses(health));
                    }
                    if (operationalMetrics != null) {
                        operational.put(AnalyticsMetric.Operational.Request.COUNTERS, operationalMetrics.snapshot(AnalyticsMetric.Operational.Metric.Request.COUNTERS));
                        operational.put(AnalyticsMetric.Operational.Request.RATE_LIMIT_REJECTIONS, operationalMetrics.snapshot(AnalyticsMetric.Operational.Metric.RateLimit.REJECTIONS));
                    }
                    summary.put(AnalyticsMetric.Summary.General.Operational.REPORT, operational);
                }
                long rangeDays = ChronoUnit.DAYS.between(job.getFromDate(), job.getToDate()) + 1;
                LocalDateTime previousFrom = from.minusDays(rangeDays);
                LocalDateTime previousTo = from.minusNanos(1);
                Map<AnalyticsMetric.Key, Object> comparison = new LinkedHashMap<>();
                comparison.put(AnalyticsMetric.Summary.User.Registration.NEW, Map.of(
                        AnalyticsMetric.Comparison.CURRENT, users.countByCreatedAtBetweenAndDeletedAtIsNull(from, inclusiveTo),
                        AnalyticsMetric.Comparison.PREVIOUS, users.countByCreatedAtBetweenAndDeletedAtIsNull(previousFrom, previousTo)));
                comparison.put(AnalyticsMetric.Summary.Engagement.Activity.EVENTS, Map.of(
                        AnalyticsMetric.Comparison.CURRENT, countFilteredEvents(job, from, inclusiveTo),
                        AnalyticsMetric.Comparison.PREVIOUS, countFilteredEvents(job, previousFrom, previousTo)));
                summary.put(AnalyticsMetric.Summary.General.Period.COMPARISON, comparison);
                Map<AnalyticsMetric.Result.Key, Object> result = new LinkedHashMap<>();
                result.put(AnalyticsMetric.Result.Report.TYPE, job.getReportType());
                result.put(AnalyticsMetric.Result.Date.FROM, job.getFromDate());
                result.put(AnalyticsMetric.Result.Date.TO, job.getToDate());
                result.put(AnalyticsMetric.Result.Request.BUCKET, job.getBucket());
                result.put(AnalyticsMetric.Result.Content.SUMMARY, summary);
                result.put(AnalyticsMetric.Result.Content.TABLES, buildTables(job, summary));
                List<Map<AnalyticsMetric.Series.Key, Object>> allSeries = buildSeries(job);
                sortSeries(allSeries, job);
                if (allSeries.size() > jobProperties.getMaximumResultRows()) {
                    throw new BadRequestException("Analytics result exceeds configured row limit");
                }
                if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(properties.getQueryTimeout()) > 0) {
                    throw new BadRequestException("Analytics query exceeded configured timeout");
                }
                long requestedPageStart = (long) job.getPageNumber() * job.getPageSize();
                int pageStart = requestedPageStart >= allSeries.size()
                        ? allSeries.size() : (int) requestedPageStart;
                int pageEnd = Math.min(pageStart + job.getPageSize(), allSeries.size());
                result.put(AnalyticsMetric.Result.Content.SERIES, PaginatedResponse.<Map<AnalyticsMetric.Series.Key, Object>>builder()
                        .content(allSeries.subList(pageStart, pageEnd))
                        .pageNumber(job.getPageNumber())
                        .pageSize(job.getPageSize())
                        .totalElements(allSeries.size())
                        .totalPages(allSeries.isEmpty() ? 0 : (allSeries.size() + job.getPageSize() - 1) / job.getPageSize())
                        .last(pageEnd >= allSeries.size())
                        .build());
                String serializedResult = objectMapper.writeValueAsString(result);
                if (serializedResult.getBytes(StandardCharsets.UTF_8).length > jobProperties.getMaximumResultBytes()) {
                    throw new BadRequestException("Analytics result exceeds configured byte limit");
                }
                job.setResultJson(serializedResult);
                materializeArtifact(job, serializedResult);
                job.setStatus(AnalyticsJobStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now(clock));
                jobs.save(job);
                notifyOwner(job, null);
            } catch (Exception ex) {
                log.error("Analytics job {} failed", id, ex);
                job.setStatus(AnalyticsJobStatus.FAILED);
                job.setFailureMessage(ex.getMessage()); jobs.save(job);
                notifyOwner(job, ex.getMessage());
            }
            });
        } finally {
            concurrencyLimiter.release();
        }
    }

    private Map<String, String> healthComponentStatuses(HealthDescriptor descriptor) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (descriptor instanceof CompositeHealthDescriptor composite) {
            composite.getComponents().forEach((name, component) ->
                    statuses.put(name, component.getStatus().getCode()));
        }
        return statuses;
    }

    private void notifyOwner(AnalyticsJob job, String failure) {
        if (messagingTemplate == null || chatProperties == null) return;
        users.findById(job.getOwnerId()).ifPresent(owner -> messagingTemplate.convertAndSendToUser(
                owner.getEmail(), chatProperties.getNotificationDestination(),
                new AnalyticsJobEvent(job.getId(), job.getStatus(), job.getReportType(), failure)));
    }

    @Transactional(readOnly = true)
    public AnalyticsJobResponse get(String id, String username) {
        AnalyticsJob job = ownerJob(id, username);
        audit(AuditLogAction.Analytics.Result.READ, job, AnalyticsAuditOutcome.STATUS);
        return response(job);
    }

    @Transactional(readOnly = true)
    public AnalyticsResult result(String id, String username) {
        AnalyticsJob job = ownerJob(id, username);
        if (job.getStatus() != AnalyticsJobStatus.COMPLETED || job.getResultJson() == null) {
            throw new BadRequestException("Analytics result is not ready");
        }
        audit(AuditLogAction.Analytics.Result.READ, job, AnalyticsAuditOutcome.SUCCESS);
        try {
            return objectMapper.readValue(job.getResultJson(), AnalyticsResult.class);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Analytics result is not available", ex);
        }
    }

    @Transactional(readOnly = true)
    public Download download(String id, String username) {
        AnalyticsJob job = ownerJob(id, username);
        if (job.getStatus() != AnalyticsJobStatus.COMPLETED || job.getResultJson() == null) {
            throw new BadRequestException("Analytics result is not ready");
        }
        audit(AuditLogAction.Analytics.EXPORT, job, AnalyticsAuditOutcome.SUCCESS);
        AnalyticsJobArtifact artifact = artifacts.findFirstByJobIdOrderByCreatedAtDesc(job.getId()).orElse(null);
        if (artifact != null && storageService != null) {
            try {
                StorageResource resource = storageService.retrieve(artifact.getStorageKey());
                return new Download(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8),
                        "text/csv".equals(artifact.getContentType()));
            } catch (IOException e) { throw new BadRequestException("Analytics artifact is not available", e); }
        }
        if (job.getOutputFormat() != AnalyticsOutputFormat.CSV) return new Download(job.getResultJson(), false);
        try {
            return new Download(toCsv(job.getResultJson()), true);
        } catch (Exception e) { throw new BadRequestException("Analytics result is not available", e); }
    }

    private void materializeArtifact(AnalyticsJob job, String json) {
        if (storageService == null) return;
        boolean csv = job.getOutputFormat() == AnalyticsOutputFormat.CSV;
        byte[] content = (csv ? toCsv(json) : json).getBytes(StandardCharsets.UTF_8);
        String filename = exportProperties.getStoragePrefix() + "/analytics-" + job.getId() + (csv ? ".csv" : ".json");
        var stored = storageService.store(new ByteArrayInputStream(content), filename,
                csv ? "text/csv" : "application/json", content.length);
        AnalyticsJobArtifact artifact = new AnalyticsJobArtifact();
        artifact.setJobId(job.getId()); artifact.setStorageKey(stored.key()); artifact.setFileName(filename);
        artifact.setContentType(csv ? "text/csv" : "application/json"); artifact.setSize(content.length);
        artifact.setSha256(sha256(content)); artifact.setExpiresAt(LocalDateTime.now(clock).plus(exportProperties.getRetention()));
        artifacts.save(artifact);
    }

    private String toCsv(String json) {
        try {
            var root = objectMapper.readTree(json);
            var summary = root.path(AnalyticsMetric.Result.Content.SUMMARY.value());
            var series = root.path(AnalyticsMetric.Result.Content.SERIES.value()).path(AnalyticsMetric.Result.Content.CONTENT.value());
            StringBuilder csv = new StringBuilder("section,key,value\n");
            summary.properties().forEach(e -> csv.append(csvCell(AnalyticsMetric.Result.Content.SUMMARY.value())).append(',')
                    .append(csvCell(e.getKey())).append(',')
                    .append(csvCell(e.getValue().isContainerNode() ? e.getValue().toString() : e.getValue().asText()))
                    .append('\n'));
            for (int index = 0; index < series.size(); index++) {
                final int pointIndex = index;
                var point = series.get(index);
                point.properties().forEach(e -> csv.append(csvCell(AnalyticsMetric.Csv.SERIES_PREFIX.value() + pointIndex + "]")).append(',')
                        .append(csvCell(e.getKey())).append(',')
                        .append(csvCell(e.getValue().isContainerNode() ? e.getValue().toString() : e.getValue().asText()))
                        .append('\n'));
            }
            var tables = root.path(AnalyticsMetric.Result.Content.TABLES.value());
            tables.properties().forEach(table -> {
                var content = table.getValue().path(AnalyticsMetric.Result.Content.CONTENT.value());
                for (int index = 0; index < content.size(); index++) {
                    var row = content.get(index);
                    row.properties().forEach(entry -> csv.append(csvCell(AnalyticsMetric.Csv.TABLE_PREFIX.value() + table.getKey()))
                            .append(',').append(csvCell(entry.getKey())).append(',')
                            .append(csvCell(entry.getValue().isContainerNode()
                                    ? entry.getValue().toString() : entry.getValue().asText()))
                            .append('\n'));
                }
            });
            return csv.toString();
        } catch (JsonProcessingException e) { throw new BadRequestException("Analytics result is not available", e); }
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    public record Download(String content, boolean csv) { }

    @Transactional
    public void delete(String id, String username) {
        AnalyticsJob job = ownerJob(id, username);
        artifacts.findFirstByJobIdOrderByCreatedAtDesc(job.getId()).ifPresent(artifact -> {
            if (storageService != null) {
                try { storageService.delete(artifact.getStorageKey()); }
                catch (RuntimeException ex) { log.warn("Could not remove analytics artifact {}", artifact.getStorageKey(), ex); }
            }
            artifacts.delete(artifact);
        });
        jobs.delete(job);
    }

    private AnalyticsJob ownerJob(String id, String username) {
        String owner = users.findByEmail(username).map(u -> u.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated administrator not found"));
        AnalyticsJob job = jobs.findByIdAndOwnerId(id, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Analytics job not found"));
        return job;
    }

    private void audit(AuditLogAction.Key action, AnalyticsJob job, AnalyticsAuditOutcome outcome) {
        if (auditLogService == null) return;
        auditLogService.logAction(action, auditDetails(job, outcome));
    }

    private String auditDetails(AnalyticsJob job, AnalyticsAuditOutcome outcome) {
        return AnalyticsMetadata.Audit.Job.ID.value() + "=" + job.getId()
                + "," + AnalyticsMetadata.Audit.Report.TYPE.value() + "=" + job.getReportType()
                + "," + AnalyticsMetadata.Audit.Range.VALUE.value() + "=" + job.getFromDate() + ".." + job.getToDate()
                + "," + AnalyticsMetadata.Audit.Filter.VALUE.value() + "=" + (job.getFiltersJson() == null ? "{}" : job.getFiltersJson())
                + "," + AnalyticsMetadata.Audit.Permission.SCOPE.value() + "=" + job.getPermissionScope()
                + "," + AnalyticsMetadata.Audit.Outcome.VALUE.value() + "=" + outcome.value();
    }

    private void validate(AnalyticsJobRequest r, boolean financial) {
        if (r.getReportType() == null || r.getBucket() == null || r.getFromDate() == null || r.getToDate() == null) throw new BadRequestException("Report type, range, and bucket are required");
        if (r.getToDate().isBefore(r.getFromDate())) throw new BadRequestException("Analytics end date must not precede start date");
        if (properties.getSupportedBuckets() != null && !properties.getSupportedBuckets().isEmpty()
                && !properties.getSupportedBuckets().contains(r.getBucket())) {
            throw new BadRequestException("Analytics bucket is not enabled by configuration");
        }
        long days = ChronoUnit.DAYS.between(r.getFromDate(), r.getToDate()) + 1;
        if (days > properties.getMaximumRangeDays()) throw new BadRequestException("Analytics date range exceeds configured maximum");
        long estimatedBuckets = switch (r.getBucket()) {
            case DAY -> days;
            case WEEK -> (days + 6) / 7;
            case MONTH -> (days + 30) / 31;
            case QUARTER -> (days + 89) / 90;
        };
        if (estimatedBuckets > properties.getMaximumBucketCount()) throw new BadRequestException("Analytics bucket count exceeds configured maximum");
        int pageSize = r.getPageSize() == null ? properties.getDefaultPageSize() : r.getPageSize();
        if (pageSize < 1 || pageSize > properties.getMaximumPageSize()) throw new BadRequestException("Analytics page size is outside configured bounds");
        if (r.getPageNumber() != null && r.getPageNumber() < 0) throw new BadRequestException("Analytics page number must not be negative");
        if (r.getReportType() == AnalyticsReportType.Subscriptions.PAYMENTS && !financial) {
            throw new ForbiddenException("Financial analytics permission is required");
        }
        if (r.getOutputFormat() != null && r.getOutputFormat() != AnalyticsOutputFormat.JSON
                && r.getOutputFormat() != AnalyticsOutputFormat.CSV) throw new BadRequestException("Output format must be JSON or CSV");
        if (r.getReportType() == AnalyticsReportType.Csv.EXPORT
                && r.getOutputFormat() != AnalyticsOutputFormat.CSV) {
            throw new BadRequestException("CSV_EXPORT reports require CSV output format");
        }
        if (r.getFilters() != null && r.getFilters().containsKey(AnalyticsFilterKey.Event.TYPE)
                && AnalyticsEvent.fromValue(r.getFilters().get(AnalyticsFilterKey.Event.TYPE)).isEmpty()) {
            throw new BadRequestException("Unsupported analytics event type filter");
        }
    }

    private List<Map<AnalyticsMetric.Series.Key, Object>> buildSeries(AnalyticsJob job) {
        List<Map<AnalyticsMetric.Series.Key, Object>> series = new ArrayList<>();
        Map<AnalyticsFilterKey.Event, String> filters = readFilters(job);
        LocalDate cursor = firstBucketDate(job.getFromDate(), job.getBucket());
        while (!cursor.isAfter(job.getToDate())) {
            LocalDate next = switch (job.getBucket()) {
                case DAY -> cursor.plusDays(1);
                case WEEK -> cursor.plusWeeks(1);
                case MONTH -> cursor.with(TemporalAdjusters.firstDayOfNextMonth());
                case QUARTER -> cursor.plusMonths(3).with(TemporalAdjusters.firstDayOfMonth());
            };
            LocalDate start = cursor.isBefore(job.getFromDate()) ? job.getFromDate() : cursor;
            LocalDate end = next.minusDays(1).isAfter(job.getToDate()) ? job.getToDate() : next.minusDays(1);
            LocalDateTime bucketFrom = utcStart(start);
            LocalDateTime bucketTo = utcStart(end.plusDays(1));
            Map<AnalyticsMetric.Series.Key, Object> point = new LinkedHashMap<>();
            point.put(AnalyticsMetric.Series.Date.START, start);
            point.put(AnalyticsMetric.Series.Date.END, end);
            String eventTypeValue = filters.get(AnalyticsFilterKey.Event.TYPE);
            AnalyticsEvent.Type eventType = eventTypeValue == null
                    ? null : AnalyticsEvent.fromValue(eventTypeValue).orElse(null);
            LocalDateTime inclusiveBucketTo = bucketTo.minusNanos(1);
            point.put(AnalyticsMetric.Series.Activity.EVENTS, eventType == null
                    ? events.countByEventTimeBetween(bucketFrom, inclusiveBucketTo)
                    : events.countByEventTypeAndEventTimeBetween(eventType, bucketFrom, inclusiveBucketTo));
            point.put(AnalyticsMetric.Series.Actor.UNIQUE, eventType == null
                    ? events.countDistinctActorsByEventTimeBetween(bucketFrom, inclusiveBucketTo)
                    : events.countDistinctActorsByTypeAndEventTimeBetween(eventType, bucketFrom, inclusiveBucketTo));
            point.put(AnalyticsMetric.Series.Registration.NEW, users.countByCreatedAtBetweenAndDeletedAtIsNull(bucketFrom, inclusiveBucketTo));
            series.add(point);
            cursor = next;
        }
        return series;
    }

    private void sortSeries(List<Map<AnalyticsMetric.Series.Key, Object>> series, AnalyticsJob job) {
        AnalyticsSeriesSorter.sort(series, job.getSortField(), job.getSortDirection());
    }

    private List<Map<AnalyticsMetric.Key, Object>> loginRetentionCohorts(LocalDateTime from, LocalDateTime to) {
        Map<String, LocalDateTime> firstRegistrationByActor = new LinkedHashMap<>();
        for (var event : events.findByEventTypeAndEventTimeBetweenOrderByEventTimeAsc(
                AnalyticsEvent.Registration.CREATED, from, to)) {
            if (event.getActorId() != null) {
                firstRegistrationByActor.putIfAbsent(event.getActorId(), event.getEventTime());
            }
        }
        if (firstRegistrationByActor.isEmpty()) return List.of();
        Map<AnalyticsMetric.Retention.Day, Set<String>> retainedByWindow = new LinkedHashMap<>();
        retainedByWindow.put(AnalyticsMetric.Retention.Day.ONE, new HashSet<>());
        retainedByWindow.put(AnalyticsMetric.Retention.Day.SEVEN, new HashSet<>());
        retainedByWindow.put(AnalyticsMetric.Retention.Day.THIRTY, new HashSet<>());
        for (var event : events.findByEventTypeAndEventTimeBetweenOrderByEventTimeAsc(
                AnalyticsEvent.Authentication.Login.SUCCEEDED, from, to.plusDays(30))) {
            LocalDateTime cohort = firstRegistrationByActor.get(event.getActorId());
            if (cohort == null) continue;
            long age = ChronoUnit.DAYS.between(businessDate(cohort), businessDate(event.getEventTime()));
            if (age == 1) retainedByWindow.get(AnalyticsMetric.Retention.Day.ONE).add(event.getActorId());
            if (age == 7) retainedByWindow.get(AnalyticsMetric.Retention.Day.SEVEN).add(event.getActorId());
            if (age == 30) retainedByWindow.get(AnalyticsMetric.Retention.Day.THIRTY).add(event.getActorId());
        }
        Map<LocalDate, Long> cohortSizes = firstRegistrationByActor.values().stream()
                .collect(Collectors.groupingBy(this::businessDate,
                        LinkedHashMap::new, Collectors.counting()));
        return cohortSizes.entrySet().stream().map(entry -> {
            long size = entry.getValue();
            Map<AnalyticsMetric.Key, Object> row = new LinkedHashMap<>();
            row.put(AnalyticsMetric.Retention.Row.Cohort.DATE, entry.getKey());
            row.put(AnalyticsMetric.Retention.Row.Cohort.SIZE, size);
            for (Map.Entry<AnalyticsMetric.Retention.Day, Set<String>> window : retainedByWindow.entrySet()) {
                long retained = window.getValue().stream()
                        .filter(firstRegistrationByActor::containsKey)
                        .filter(actor -> businessDate(firstRegistrationByActor.get(actor)).equals(entry.getKey()))
                        .count();
                row.put(window.getKey().retainedRow(), retained);
                row.put(window.getKey().rateRow(), size == 0 ? 0.0 : (double) retained / size);
            }
            return row;
        }).toList();
    }

    private LocalDate businessDate(LocalDateTime timestamp) {
        return timestamp.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.of(properties.getBusinessTimeZone()))
                .toLocalDate();
    }

    private Map<AnalyticsMetric.Key, PaginatedResponse<Map<AnalyticsMetric.Csv, Object>>> buildTables(AnalyticsJob job,
                                                                                                           Map<AnalyticsMetric.Key, Object> summary) {
        Map<AnalyticsMetric.Key, PaginatedResponse<Map<AnalyticsMetric.Csv, Object>>> tables = new LinkedHashMap<>();
        switch (job.getReportType().family()) {
            case MODERATION -> {
                addStatusTable(tables, AnalyticsMetric.Table.User.USERS, summary.get(AnalyticsMetric.Summary.User.Status.ALL), job);
                addStatusTable(tables, AnalyticsMetric.Table.Formateur.REQUESTS, summary.get(AnalyticsMetric.Summary.Moderation.Formateur.Statuses.VALUE), job);
                addStatusTable(tables, AnalyticsMetric.Table.Report.REPORTS, summary.get(AnalyticsMetric.Summary.Report.Status.By.STATUS), job);
            }
            case CONTENT -> {
                addStatusTable(tables, AnalyticsMetric.Table.Feed.POSTS, summary.get(AnalyticsMetric.Summary.Content.Feed.BY_STATUS), job);
                addStatusTable(tables, AnalyticsMetric.Table.Formation.FORMATIONS, summary.get(AnalyticsMetric.Summary.Formation.Status.By.STATUS), job);
                addStatusTable(tables, AnalyticsMetric.Table.Enrollment.ENROLLMENTS, summary.get(AnalyticsMetric.Summary.Formation.Enrollment.By.STATUS), job);
            }
            case SUBSCRIPTIONS -> {
                addStatusTable(tables, AnalyticsMetric.Table.Payment.PAYMENTS, summary.get(AnalyticsMetric.Summary.Payment.Status.By.STATUS), job);
                addStatusTable(tables, AnalyticsMetric.Table.Subscription.ALL, summary.get(AnalyticsMetric.Summary.Subscription.Status.By.STATUS), job);
                if (summary.get(AnalyticsMetric.Summary.Subscription.Subscriber.TYPE) instanceof Map<?, ?> byType) {
                    addStatusTable(tables, AnalyticsMetric.Table.Subscription.ARTISAN, byType.get(AnalyticsMetric.AccountType.ARTISAN), job);
                    addStatusTable(tables, AnalyticsMetric.Table.Subscription.CLIENT, byType.get(AnalyticsMetric.AccountType.CLIENT), job);
                }
            }
            case ENGAGEMENT, TIME_SERIES -> addStatusTable(tables, AnalyticsMetric.Table.Activity.EVENTS, Map.of(
                    AnalyticsMetric.Summary.Engagement.Activity.EVENTS, summary.getOrDefault(AnalyticsMetric.Summary.Engagement.Activity.EVENTS, 0L),
                    AnalyticsMetric.Summary.Engagement.Message.SENT, summary.getOrDefault(AnalyticsMetric.Summary.Engagement.Message.SENT, 0L),
                    AnalyticsMetric.Summary.Engagement.Post.PUBLISHED, summary.getOrDefault(AnalyticsMetric.Summary.Engagement.Post.PUBLISHED, 0L),
                    AnalyticsMetric.Summary.Engagement.Profile.VIEWS, summary.getOrDefault(AnalyticsMetric.Summary.Engagement.Profile.VIEWS, 0L),
                    AnalyticsMetric.Summary.Engagement.Report.RESOLUTIONS, summary.getOrDefault(AnalyticsMetric.Summary.Engagement.Report.RESOLUTIONS, 0L)), job);
            default -> { }
        }
        return tables;
    }

    private void addStatusTable(Map<AnalyticsMetric.Key, PaginatedResponse<Map<AnalyticsMetric.Csv, Object>>> tables,
                                 AnalyticsMetric.Key table, Object source, AnalyticsJob job) {
        if (!(source instanceof Map<?, ?> values)) return;
        List<Map<AnalyticsMetric.Csv, Object>> rows = values.entrySet().stream()
                .map(entry -> Map.<AnalyticsMetric.Csv, Object>of(AnalyticsMetric.Csv.KEY, keyValue(entry.getKey()),
                        AnalyticsMetric.Csv.VALUE, entry.getValue() == null ? 0L : entry.getValue()))
                .sorted((left, right) -> String.valueOf(left.get(AnalyticsMetric.Csv.KEY)).compareTo(String.valueOf(right.get(AnalyticsMetric.Csv.KEY))))
                .toList();
        int pageSize = job.getPageSize();
        long requestedStart = (long) job.getPageNumber() * pageSize;
        int start = requestedStart >= rows.size() ? rows.size() : (int) requestedStart;
        int end = Math.min(start + pageSize, rows.size());
        tables.put(table, PaginatedResponse.<Map<AnalyticsMetric.Csv, Object>>builder()
                .content(rows.subList(start, end)).pageNumber(job.getPageNumber()).pageSize(pageSize)
                .totalElements(rows.size()).totalPages(rows.isEmpty() ? 0 : (rows.size() + pageSize - 1) / pageSize)
                .last(end >= rows.size()).build());
    }

    private String keyValue(Object key) {
        if (key instanceof EnumValue enumValue) return enumValue.value();
        return String.valueOf(key);
    }

    private Map<String, Long> dimensionCounts(List<AnalyticsDimensionCount> counts) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (AnalyticsDimensionCount count : counts) {
            result.put(count.getDimension(), count.getCount());
        }
        return result;
    }

    private Map<AnalyticsFilterKey.Event, String> readFilters(AnalyticsJob job) {
        try {
            if (job.getFiltersJson() == null || job.getFiltersJson().isBlank()) return Map.of();
            return objectMapper.readValue(job.getFiltersJson(), new AnalyticsFiltersTypeReference());
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Analytics filters are not readable", ex);
        }
    }

    private AnalyticsEvent.Type eventTypeFilter(AnalyticsJob job) {
        String value = readFilters(job).get(AnalyticsFilterKey.Event.TYPE);
        return value == null ? null : AnalyticsEvent.fromValue(value).orElseThrow();
    }

    private long countFilteredEvents(AnalyticsJob job, LocalDateTime from, LocalDateTime to) {
        AnalyticsEvent.Type eventType = eventTypeFilter(job);
        return eventType == null ? events.countByEventTimeBetween(from, to)
                : events.countByEventTypeAndEventTimeBetween(eventType, from, to);
    }

    private long countFilteredEvent(AnalyticsJob job, AnalyticsEvent.Type eventType, LocalDateTime from, LocalDateTime to) {
        AnalyticsEvent.Type filter = eventTypeFilter(job);
        return filter != null && !filter.equals(eventType) ? 0L
                : events.countByEventTypeAndEventTimeBetween(eventType, from, to);
    }

    private long countFilteredDistinctActors(AnalyticsJob job, LocalDateTime from, LocalDateTime to) {
        AnalyticsEvent.Type eventType = eventTypeFilter(job);
        return eventType == null ? events.countDistinctActorsByEventTimeBetween(from, to)
                : events.countDistinctActorsByTypeAndEventTimeBetween(eventType, from, to);
    }

    private LocalDate firstBucketDate(LocalDate date, AnalyticsBucket bucket) {
        return switch (bucket) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.with(TemporalAdjusters.firstDayOfMonth());
            case QUARTER -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1)
                    .with(TemporalAdjusters.firstDayOfMonth());
        };
    }

    private LocalDateTime utcStart(LocalDate date) {
        ZoneId businessZone = ZoneId.of(properties.getBusinessTimeZone());
        ZonedDateTime start = date.atStartOfDay(businessZone);
        return start.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private AnalyticsJobResponse response(AnalyticsJob j) { return AnalyticsJobResponse.builder().id(j.getId()).reportType(j.getReportType()).status(j.getStatus()).bucket(j.getBucket()).fromDate(j.getFromDate()).toDate(j.getToDate()).pageNumber(j.getPageNumber()).pageSize(j.getPageSize()).outputFormat(j.getOutputFormat()).completedAt(j.getCompletedAt()).expiresAt(j.getExpiresAt()).failureMessage(j.getFailureMessage()).build(); }
}
