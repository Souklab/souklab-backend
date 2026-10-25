package com.project.souklab.analytics;

import org.mockito.Mockito;

import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.dao.analytics.AnalyticsMaintenanceJobRepository;
import com.project.souklab.dto.analytics.AnalyticsRebuildRequest;
import com.project.souklab.dto.analytics.AnalyticsRebuildResponse;
import com.project.souklab.dto.analytics.AnalyticsMaintenanceJobEvent;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.model.User;
import com.project.souklab.model.analytics.AnalyticsJobStatus;
import com.project.souklab.model.analytics.AnalyticsMaintenanceJob;
import com.project.souklab.model.analytics.AnalyticsMaintenanceOperation;
import com.project.souklab.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsMaintenanceJobServiceTest {
    @Mock private AnalyticsMaintenanceJobRepository jobs;
    @Mock private UserRepository users;
    @Mock private AnalyticsProperties properties;
    @Mock private AnalyticsRebuildService rebuildService;
    @Mock private AnalyticsBackfillService backfillService;
    @Mock private AuditLogService auditLogService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private Executor executor;
    @Mock private TransactionTemplate transactionTemplate;

    private AnalyticsMaintenanceJobService service;
    private AnalyticsMaintenanceJob job;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC);
        service = new AnalyticsMaintenanceJobService(jobs, users, properties, rebuildService,
                backfillService, clock, executor, transactionTemplate, auditLogService);
        service.setMessagingTemplate(messagingTemplate);
        ChatProperties chatProperties = new ChatProperties();
        chatProperties.setNotificationDestination("/queue/analytics");
        service.setChatProperties(chatProperties);
        job = new AnalyticsMaintenanceJob();
        job.setId("maintenance-1");
        job.setOwnerId("owner-1");
        job.setOperation(AnalyticsMaintenanceOperation.REBUILD);
        job.setStatus(AnalyticsJobStatus.QUEUED);
        job.setFromDate(LocalDate.of(2026, 1, 1));
        job.setToDate(LocalDate.of(2026, 1, 2));
        lenient().when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("owner-1")));
        lenient().when(users.findById("owner-1")).thenReturn(Optional.of(user("owner-1")));
        lenient().when(properties.getJobRetention()).thenReturn(Duration.ofHours(24));
        lenient().when(properties.getMaximumRangeDays()).thenReturn(366);
        lenient().when(jobs.saveAndFlush(any(AnalyticsMaintenanceJob.class))).thenAnswer(invocation -> {
            AnalyticsMaintenanceJob saved = invocation.getArgument(0);
            saved.setId("maintenance-1");
            job = saved;
            return saved;
        });
        lenient().when(jobs.findById("maintenance-1")).thenAnswer(invocation -> Optional.of(job));
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        lenient().doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void completesAndPersistsCountsAfterWorkerExecution() {
        when(rebuildService.rebuild(job.getFromDate(), job.getToDate()))
                .thenReturn(new AnalyticsRebuildResponse(job.getFromDate(), job.getToDate(), 8, 4));

        var response = service.submit(AnalyticsMaintenanceOperation.REBUILD,
                new AnalyticsRebuildRequest(job.getFromDate(), job.getToDate()), "admin@example.com");

        assertThat(response.getId()).isEqualTo("maintenance-1");
        assertThat(job.getStatus()).isEqualTo(AnalyticsJobStatus.COMPLETED);
        assertThat(job.getEventsRead()).isEqualTo(8);
        assertThat(job.getRollupsWritten()).isEqualTo(4);
        verify(jobs, Mockito.times(2)).save(job);
        verify(messagingTemplate).convertAndSendToUser(eq("admin@example.com"), eq("/queue/analytics"),
                any(AnalyticsMaintenanceJobEvent.class));
    }

    @Test
    void persistsFailureAfterRebuildTransactionFails() {
        when(rebuildService.rebuild(job.getFromDate(), job.getToDate()))
                .thenThrow(new IllegalStateException("rollup unavailable"));

        service.submit(AnalyticsMaintenanceOperation.REBUILD,
                new AnalyticsRebuildRequest(job.getFromDate(), job.getToDate()), "admin@example.com");

        assertThat(job.getStatus()).isEqualTo(AnalyticsJobStatus.FAILED);
        assertThat(job.getFailureMessage()).isEqualTo("rollup unavailable");
        verify(jobs, Mockito.times(2)).save(job);
        verify(messagingTemplate).convertAndSendToUser(eq("admin@example.com"), eq("/queue/analytics"),
                any(AnalyticsMaintenanceJobEvent.class));
    }

    @Test
    void statusCannotBeReadByAnotherOwner() {
        when(jobs.findByIdAndOwnerId("maintenance-1", "owner-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("maintenance-1", "admin@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsInvalidRangeBeforeCreatingAJob() {
        AnalyticsRebuildRequest invalid = new AnalyticsRebuildRequest(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> service.submit(AnalyticsMaintenanceOperation.REBUILD,
                invalid, "admin@example.com"))
                .isInstanceOf(BadRequestException.class);
    }

    private User user(String id) {
        User user = new User();
        user.setId(id);
        user.setEmail("admin@example.com");
        return user;
    }
}
