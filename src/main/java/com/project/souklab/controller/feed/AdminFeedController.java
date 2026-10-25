package com.project.souklab.controller.feed;

import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.feed.FeedPostModerationDTO;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.service.feed.FeedPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator moderation endpoints for community feed posts.
 */
@Tag(name = "Community Feed Administration", description = "Administrator moderation queue and lifecycle actions for feed posts")
@RestController
@RequestMapping("/api/v1/admin/feed")
@PreAuthorize("@accessControl.canModerateFeed(authentication)")
@RequiredArgsConstructor
public class AdminFeedController {

    private final FeedPostService feedPostService;

    /**
     * Lists posts awaiting moderation.
     *
     * @param pageable pagination configuration
     * @return pending posts
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostResponseDTO>>> listPending(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PaginatedResponse.from(feedPostService.listPending(pageable))));
    }

    /**
     * Retrieves a feed post by ID for administrative review.
     *
     * @param id post identifier
     * @return feed post response DTO
     */
    @Operation(summary = "Get feed post for administration", description = "Retrieves a single feed post by ID for administrative review.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> getById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.getForCaller(id)));
    }

    /**
     * Publishes a pending post.
     *
     * @param id post identifier
     * @param request moderation note
     * @return published post
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> publish(@PathVariable String id, @Valid @RequestBody FeedPostModerationDTO request) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.publish(id, request), "Feed post published."));
    }

    /**
     * Hides a post.
     *
     * @param id post identifier
     * @param request moderation note
     * @return hidden post
     */
    @PostMapping("/{id}/hide")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> hide(@PathVariable String id, @Valid @RequestBody FeedPostModerationDTO request) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.hide(id, request), "Feed post hidden."));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> reject(@PathVariable String id,
                                                                     @Valid @RequestBody FeedPostModerationDTO request) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.reject(id, request), "Feed post rejected."));
    }

    /**
     * Removes a post as an administrator.
     *
     * @param id post identifier
     * @return empty success response
     */
    @PostMapping("/{id}/remove")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable String id) {
        feedPostService.remove(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Feed post removed."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        return remove(id);
    }
}
