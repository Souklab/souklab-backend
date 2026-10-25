package com.project.souklab.analytics;

import com.project.souklab.config.AnalyticsProperties;
import com.project.souklab.config.ChatProperties;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dao.analytics.AnalyticsMaintenanceJobRepository;
import com.project.souklab.dto.analytics.AnalyticsMaintenanceJobEvent;
import com.project.souklab.dto.analytics.AnalyticsMaintenanceJobResponse;
import com.project.souklab.dto.analytics.AnalyticsRebuildRequest;
import com.project.souklab.dto.analytics.AnalyticsRebuildResponse;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.analytics.AnalyticsJobStatus;
import com.project.souklab.model.analytics.AnalyticsAuditOutcome;
import com.project.souklab.model.analytics.AnalyticsMaintenanceJob;
import com.project.souklab.model.analytics.AnalyticsMaintenanceOperation;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.security.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsMaintenanceJobService {
    private final AnalyticsMaintenanceJobRepository jobs;
    private final UserRepository users;
    private final AnalyticsProperties properties;
    private final AnalyticsRebuildService rebuildService;
    private final AnalyticsBackfillService backfillService;
    private final Clock clock;
    @Qualifier("applicationTaskExecutor")
    private final Executor applicationTaskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final AuditLogService auditLogService;
    private SimpMessagingTemplate messagingTemplate;
    private ChatProperties chatProperties;

    @Autowired(required = false)
    void setMessagingTemplate(SimpMessagingTemplate value) { this.messagingTemplate = value; }

    @Autowired(required = false)
    void setChatProperties(ChatProperties value) { this.chatProperties = value; }

    public AnalyticsMaintenanceJobResponse submit(AnalyticsMaintenanceOperation operation,
                                                   AnalyticsRebuildRequest request,
                                                   String username) {
        validateRange(request);
        String ownerId = users.findByEmail(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated administrator not found")).getId();
        AnalyticsMaintenanceJob job = new AnalyticsMaintenanceJob();
        job.setOwnerId(ownerId);
        job.setOperation(operation);
        job.setStatus(AnalyticsJobStatus.QUEUED);
        job.setFromDate(request.fromDate());
        job.setToDate(request.toDate());
        job.setPermissionScope(Permission.Analytics.ADMIN.value());
        job.setExpiresAt(LocalDateTime.now(clock).plus(properties.getJobRetention()));
        AnalyticsMaintenanceJob saved = jobs.saveAndFlush(job);
        auditLogService.logAction(AuditLogAction.Analytics.REBUILD,
                AnalyticsMetadata.Audit.MaintenanceJob.ID.value() + "=" + saved.getId()
                        + "," + AnalyticsMetadata.Audit.Operation.VALUE.value() + "=" + operation
                        + "," + AnalyticsMetadata.Audit.Range.VALUE.value() + "=" + request.fromDate() + ".." + request.toDate()
                        + "," + AnalyticsMetadata.Audit.Permission.SCOPE.value() + "=" + job.getPermissionScope()
                        + "," + AnalyticsMetadata.Audit.Outcome.VALUE.value() + "=" + AnalyticsAuditOutcome.ACCEPTED.value());
        applicationTaskExecutor.execute(() -> process(saved.getId()));
        return response(saved);
    }

    private void validateRange(AnalyticsRebuildRequest request) {
        if (request == null || request.fromDate() == null || request.toDate() == null
                || request.toDate().isBefore(request.fromDate())) {
            throw new BadRequestException("Invalid analytics maintenance date range");
        }
        long days = request.toDate().toEpochDay() - request.fromDate().toEpochDay() + 1;
        if (days > properties.getMaximumRangeDays()) {
            throw new BadRequestException("Analytics maintenance range exceeds configured maximum");
        }
    }

    public AnalyticsMaintenanceJobResponse get(String id, String username) {
        String ownerId = users.findByEmail(username)
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated administrator not found")).getId();
        AnalyticsMaintenanceJob job = jobs.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Analytics maintenance job not found"));
        auditLogService.logAction(AuditLogAction.Analytics.Result.READ,
                AnalyticsMetadata.Audit.MaintenanceJob.ID.value() + "=" + job.getId()
                        + "," + AnalyticsMetadata.Audit.Operation.VALUE.value() + "=" + job.getOperation()
                        + "," + AnalyticsMetadata.Audit.Range.VALUE.value() + "=" + job.getFromDate() + ".." + job.getToDate()
                        + "," + AnalyticsMetadata.Audit.Permission.SCOPE.value() + "=" + job.getPermissionScope()
                        + "," + AnalyticsMetadata.Audit.Outcome.VALUE.value() + "=" + AnalyticsAuditOutcome.STATUS.value());
        return response(job);
    }

    private void process(String id) {
        AnalyticsMaintenanceJob job = jobs.findById(id).orElse(null);
        if (job == null) return;
        transactionTemplate.executeWithoutResult(status -> {
            jobs.findById(id).ifPresent(current -> {
                current.setStatus(AnalyticsJobStatus.RUNNING);
                jobs.save(current);
            });
        });
        try {
            AnalyticsRebuildResponse result = job.getOperation() == AnalyticsMaintenanceOperation.REBUILD
                    ? rebuildService.rebuild(job.getFromDate(), job.getToDate())
                    : backfillService.backfill(job.getFromDate(), job.getToDate());
            transactionTemplate.executeWithoutResult(status -> jobs.findById(id).ifPresent(current -> {
                current.setEventsRead(result.eventsRead());
                current.setRollupsWritten(result.rollupsWritten());
                current.setStatus(AnalyticsJobStatus.COMPLETED);
                current.setCompletedAt(LocalDateTime.now(clock));
                jobs.save(current);
            }));
            jobs.findById(id).ifPresent(this::notifyOwner);
        } catch (Exception exception) {
            log.error("Analytics maintenance job {} failed", id, exception);
            transactionTemplate.executeWithoutResult(status -> jobs.findById(id).ifPresent(current -> {
                current.setStatus(AnalyticsJobStatus.FAILED);
                current.setFailureMessage(exception.getMessage());
                jobs.save(current);
            }));
            jobs.findById(id).ifPresent(this::notifyOwner);
        }
    }

    private void notifyOwner(AnalyticsMaintenanceJob job) {
        if (messagingTemplate == null || chatProperties == null) return;
        users.findById(job.getOwnerId()).ifPresent(owner -> messagingTemplate.convertAndSendToUser(
                owner.getEmail(), chatProperties.getNotificationDestination(),
                new AnalyticsMaintenanceJobEvent(job.getId(), job.getOperation(), job.getStatus(), job.getFailureMessage())));
    }

    private AnalyticsMaintenanceJobResponse response(AnalyticsMaintenanceJob job) {
        return AnalyticsMaintenanceJobResponse.builder()
                .id(job.getId()).operation(job.getOperation()).status(job.getStatus())
                .fromDate(job.getFromDate()).toDate(job.getToDate())
                .eventsRead(job.getEventsRead()).rollupsWritten(job.getRollupsWritten())
                .completedAt(job.getCompletedAt()).expiresAt(job.getExpiresAt())
                .failureMessage(job.getFailureMessage()).build();
    }
}
