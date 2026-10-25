package com.project.souklab.analytics;
import com.project.souklab.config.AnalyticsExportProperties;
import com.project.souklab.config.AnalyticsJobProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.config.SubscriptionProperties;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dao.ArtisanReviewRepository;
import com.project.souklab.dao.ArtisanSubscriptionRepository;
import com.project.souklab.dao.ClientSubscriptionRepository;
import com.project.souklab.dao.ContentReportRepository;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.PaymentRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dao.analytics.ActivityEventRepository;
import com.project.souklab.dao.analytics.AnalyticsJobArtifactRepository;
import com.project.souklab.dao.analytics.AnalyticsOutboxRepository;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.service.audit.AuditLogService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.dao.analytics.AnalyticsJobRepository;
import com.project.souklab.dto.analytics.AnalyticsJobRequest;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.analytics.AnalyticsBucket;
import com.project.souklab.model.analytics.AnalyticsReportType;
import com.project.souklab.model.analytics.AnalyticsJob;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.Executor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import java.util.Optional;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsJobAuthorizationTest {
    @Mock private AnalyticsJobRepository jobs;
    @Mock private ActivityEventRepository events;
    @Mock private UserRepository users;
    @Mock private AnalyticsProperties properties;
    @Mock private ObjectMapper objectMapper;
    @Mock private Clock clock;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SubscriptionProperties subscriptionProperties;
    @Mock private ChatProperties chatProperties;
    @Mock private FeedPostRepository feedPosts;
    @Mock private FormationRepository formations;
    @Mock private FormationEnrollmentRepository enrollments;
    @Mock private ArtisanReviewRepository reviews;
    @Mock private ContentReportRepository reports;
    @Mock private PaymentRepository payments;
    @Mock private AnalyticsJobArtifactRepository artifacts;
    @Mock private StorageService storageService;
    @Mock private AuditLogService auditLogService;
    @Mock private AnalyticsOutboxRepository outbox;
    @Mock private AnalyticsJobProperties jobProperties;
    @Mock private AnalyticsExportProperties exportProperties;
    @Mock private ArtisanSubscriptionRepository artisanSubscriptions;
    @Mock private ClientSubscriptionRepository clientSubscriptions;
    @Mock private ArtisanFormateurRequestRepository formateurRequests;
    @Mock private Executor applicationTaskExecutor;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private AnalyticsJobService service;

    @Test
    void financialReportRequiresFinancialPermission() {
        when(properties.getMaximumRangeDays()).thenReturn(366);
        when(properties.getMaximumBucketCount()).thenReturn(500);
        when(properties.getDefaultPageSize()).thenReturn(20);
        when(properties.getMaximumPageSize()).thenReturn(100);

        AnalyticsJobRequest request = new AnalyticsJobRequest();
        request.setReportType(AnalyticsReportType.Subscriptions.PAYMENTS);
        request.setFromDate(LocalDate.of(2026, 1, 1));
        request.setToDate(LocalDate.of(2026, 1, 1));
        request.setBucket(AnalyticsBucket.DAY);

        assertThatThrownBy(() -> service.submit(request, "admin@example.com", false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Financial analytics permission is required");
    }

    @Test
    void rejectsBucketDisabledByConfiguration() {
        when(properties.getSupportedBuckets()).thenReturn(List.of(AnalyticsBucket.DAY));

        AnalyticsJobRequest request = new AnalyticsJobRequest();
        request.setReportType(AnalyticsReportType.Overview.REPORT);
        request.setFromDate(LocalDate.of(2026, 1, 1));
        request.setToDate(LocalDate.of(2026, 1, 1));
        request.setBucket(AnalyticsBucket.WEEK);

        assertThatThrownBy(() -> service.submit(request, "admin@example.com", false))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Analytics bucket is not enabled by configuration");
    }

    @Test
    void ownerMayReadUsingCapturedFinancialScopeAfterCurrentFinancialPermissionChanges() {
        User owner = new User();
        owner.setId("owner-1");
        AnalyticsJob job = new AnalyticsJob();
        job.setId("job-1");
        job.setOwnerId("owner-1");
        job.setReportType(AnalyticsReportType.Subscriptions.PAYMENTS);
        job.setPermissionScope(Permission.Analytics.ADMIN.value() + ","
                + Permission.Financial.ADMIN.value());
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(jobs.findByIdAndOwnerId("job-1", "owner-1")).thenReturn(Optional.of(job));

        assertThat(service.get("job-1", "owner@example.com")).isNotNull();
    }

    @Test
    void nonOwnerCannotReadAnAnalyticsJob() {
        User owner = new User();
        owner.setId("different-owner");
        when(users.findByEmail("other@example.com")).thenReturn(Optional.of(owner));
        when(jobs.findByIdAndOwnerId(eq("job-1"), eq("different-owner"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("job-1", "other@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
