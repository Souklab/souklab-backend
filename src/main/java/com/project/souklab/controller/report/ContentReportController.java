package com.project.souklab.controller.report;

import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.report.ContentReportRequestDTO;
import com.project.souklab.dto.report.ContentReportResponseDTO;
import com.project.souklab.dto.report.ReportResolutionRequestDTO;
import com.project.souklab.model.ReportStatus;
import com.project.souklab.model.ReportTargetType;
import com.project.souklab.service.report.ContentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User report submission and administrator moderation endpoints.
 */
@Tag(name = "Content Moderation & Reports", description = "User complaint reporting against feed posts, reviews, or profiles, and administrator resolution")
@RestController
@RequiredArgsConstructor
public class ContentReportController {

    private final ContentReportService reportService;

    /**
     * Submits a report against a supported target.
     *
     * @param request report payload
     * @return created report
     */
    @Operation(summary = "Submit content report", description = "Submit a report against a platform resource (post, review, artisan, user) for moderation review.")
    @PostMapping("/api/v1/reports")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ContentReportResponseDTO>> create(@Valid @RequestBody ContentReportRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(reportService.create(request), "Report submitted successfully."));
    }

    /**
     * Lists reports for administrators.
     *
     * @param status optional report status
     * @param targetType optional target type
     * @param pageable pagination configuration
     * @return report queue
     */
    @Operation(summary = "List reports for moderation", description = "Administrator queue for reviewing pending or resolved content reports.")
    @GetMapping("/api/v1/admin/reports")
    @PreAuthorize("@accessControl.canModerateReports(authentication)")
    public ResponseEntity<ApiResponse<Page<ContentReportResponseDTO>>> list(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(reportService.list(status, targetType, pageable)));
    }

    @Operation(summary = "Get report details", description = "Retrieves a complete content report for administrator moderation.")
    @GetMapping("/api/v1/admin/reports/{id}")
    @PreAuthorize("@accessControl.canModerateReports(authentication)")
    public ResponseEntity<ApiResponse<ContentReportResponseDTO>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.get(id)));
    }

    /**
     * Resolves an open report with an explicit action.
     *
     * @param id report identifier
     * @param request resolution payload
     * @return resolved report
     */
    @Operation(summary = "Resolve report", description = "Administrator resolution action (dismiss, remove content, ban user) with notes.")
    @PostMapping("/api/v1/admin/reports/{id}/resolve")
    @PreAuthorize("@accessControl.canModerateReports(authentication)")
    public ResponseEntity<ApiResponse<ContentReportResponseDTO>> resolve(
            @PathVariable String id,
            @Valid @RequestBody ReportResolutionRequestDTO request) {
        return ResponseEntity.ok(ApiResponse.success(reportService.resolve(id, request), "Report resolved successfully."));
    }
}
