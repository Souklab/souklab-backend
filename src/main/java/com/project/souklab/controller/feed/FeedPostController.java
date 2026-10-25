package com.project.souklab.controller.feed;

import com.project.souklab.dto.common.ApiResponse;
import com.project.souklab.dto.feed.FeedPostCreateDTO;
import com.project.souklab.dto.feed.FeedPostMediaResponseDTO;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.dto.feed.FeedPostCommentCreateDTO;
import com.project.souklab.dto.feed.FeedPostCommentResponseDTO;
import com.project.souklab.dto.feed.FeedPostLikeStatusDTO;
import com.project.souklab.dto.feed.FeedPostLikerDTO;
import com.project.souklab.dto.feed.MediaOrderRequest;
import com.project.souklab.dto.feed.FeedShareResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.service.feed.FeedEngagementService;
import com.project.souklab.service.feed.FeedDiscoveryService;
import com.project.souklab.model.FeedPostType;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.service.feed.FeedPostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public feed and authenticated post management endpoints.
 */
@Tag(name = "Community Feed", description = "Public craft feed browsing, post publication, editing, deletion, and media attachments")
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedPostController {

    private final FeedPostService feedPostService;
    private final FeedEngagementService feedEngagementService;
    private final FeedDiscoveryService feedDiscoveryService;

    /**
     * Lists publicly published feed posts.
     *
     * @param type optional type filter
     * @param pageable pagination configuration
     * @return published posts
     */
    @Operation(summary = "List public feed posts", description = "Browse paginated feed posts published across the platform with optional type filtering.")
    @GetMapping
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostResponseDTO>>> list(
            @RequestParam(required = false) FeedPostType type,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
        Pageable pageable) {
        if (authorId == null && tag == null && q == null && sort == null) {
            return ResponseEntity.ok(ApiResponse.success(
                    PaginatedResponse.from(feedPostService.listPublic(type, pageable))));
        }
        return ResponseEntity.ok(ApiResponse.success(feedDiscoveryService.list(type, authorId, tag, q, sort, pageable)));
    }

    /**
     * Retrieves one published feed post.
     *
     * @param id post identifier
     * @return published post
     */
    @Operation(summary = "Get published feed post", description = "Retrieve a single published feed post by its identifier.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> get(@PathVariable String id,
                                                                 Authentication authentication) {
        FeedPostResponseDTO response = authentication != null && authentication.isAuthenticated()
                ? feedPostService.getForCaller(id) : feedPostService.getPublic(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/following")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostResponseDTO>>> following(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedDiscoveryService.following(pageable)));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostResponseDTO>>> mine(
            @RequestParam(required = false) FeedPostStatus status, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.listMine(status, pageable)));
    }

    /**
     * Submits a feed post for moderation.
     *
     * @param request post payload
     * @return created pending post
     */
    @Operation(summary = "Submit feed post", description = "Create and submit a new feed post for administrative moderation review.")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> create(@Valid @RequestBody FeedPostCreateDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(feedPostService.create(request), "Feed post submitted for moderation."));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> submit(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.submit(id), "Feed post submitted for moderation."));
    }

    /**
     * Updates an owned feed post.
     *
     * @param id post identifier
     * @param request post payload
     * @return updated post
     */
    @Operation(summary = "Update feed post", description = "Update the content, craft tag, or title of an owned feed post.")
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostResponseDTO>> update(@PathVariable String id, @Valid @RequestBody FeedPostCreateDTO request) {
        return ResponseEntity.ok(ApiResponse.success(feedPostService.update(id, request), "Feed post updated successfully."));
    }

    /**
     * Removes an owned feed post.
     *
     * @param id post identifier
     * @return empty success response
     */
    @Operation(summary = "Delete feed post", description = "Soft-deletes or removes an owned feed post by its identifier.")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> remove(@PathVariable String id) {
        feedPostService.remove(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Feed post removed successfully."));
    }

    /**
     * Uploads an image attachment to a post.
     *
     * @param id post identifier
     * @param file image payload
     * @return stored attachment
     */
    @Operation(summary = "Upload post media attachment", description = "Upload an image attachment for an existing authored feed post (multipart/form-data).")
    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostMediaResponseDTO>> addMedia(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(feedPostService.addMedia(id, file), "Feed media uploaded successfully."));
    }

    /**
     * Removes an image attachment from a post.
     *
     * @param id post identifier
     * @param mediaId attachment identifier
     * @return empty success response
     */
    @Operation(summary = "Delete post media attachment", description = "Removes a specific media attachment from an owned feed post.")
    @DeleteMapping("/{id}/media/{mediaId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> removeMedia(@PathVariable String id, @PathVariable String mediaId) {
        feedPostService.removeMedia(id, mediaId);
        return ResponseEntity.ok(ApiResponse.success(null, "Feed media removed successfully."));
    }

    @PutMapping("/{id}/media/order")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> reorderMedia(@PathVariable String id, @Valid @RequestBody MediaOrderRequest request) {
        feedPostService.reorderMedia(id, request.mediaIds());
        return ResponseEntity.ok(ApiResponse.success(null, "Feed media reordered successfully."));
    }

    @PostMapping("/{id}/likes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostLikeStatusDTO>> like(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(feedEngagementService.likePost(id), "Post liked."));
    }

    @DeleteMapping("/{id}/likes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> unlike(@PathVariable String id) {
        feedEngagementService.unlikePost(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Post unliked."));
    }

    @GetMapping("/{id}/likes")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostLikerDTO>>> likers(@PathVariable String id, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.likers(id, pageable)));
    }

    @GetMapping("/{id}/likes/status")
    public ResponseEntity<ApiResponse<FeedPostLikeStatusDTO>> likeStatus(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.likeStatus(id)));
    }

    @GetMapping("/{id}/likes/likers")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostLikerDTO>>> likersAlias(@PathVariable String id, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.likers(id, pageable)));
    }

    @GetMapping("/{id}/likes/users")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostLikerDTO>>> likerUsers(@PathVariable String id, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.likers(id, pageable)));
    }

    @PostMapping("/{id}/bookmarks")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> bookmark(@PathVariable String id) {
        feedEngagementService.bookmarkPost(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(null, "Post bookmarked."));
    }

    @DeleteMapping("/{id}/bookmarks")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> removeBookmark(@PathVariable String id) {
        feedEngagementService.removeBookmark(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Bookmark removed."));
    }

    @GetMapping("/saved")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostResponseDTO>>> saved(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.saved(pageable)));
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<ApiResponse<FeedShareResponseDTO>> share(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.share(id)));
    }

    @GetMapping("/{id}/comments")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostCommentResponseDTO>>> comments(
            @PathVariable String id, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.comments(id, pageable)));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostCommentResponseDTO>> comment(
            @PathVariable String id, @Valid @RequestBody FeedPostCommentCreateDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(feedEngagementService.addComment(id, request), "Comment added."));
    }

    @GetMapping("/comments/{commentId}")
    @Operation(summary = "Get feed comment", description = "Retrieve one visible comment or reply.")
    public ResponseEntity<ApiResponse<FeedPostCommentResponseDTO>> getComment(@PathVariable String commentId) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.getComment(commentId)));
    }

    @GetMapping("/comments/{commentId}/replies")
    public ResponseEntity<ApiResponse<PaginatedResponse<FeedPostCommentResponseDTO>>> replies(
            @PathVariable String commentId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.replies(commentId, pageable)));
    }

    @PostMapping("/comments/{commentId}/replies")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeedPostCommentResponseDTO>> reply(
            @PathVariable String commentId, @Valid @RequestBody FeedPostCommentCreateDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(feedEngagementService.reply(commentId, request), "Reply added."));
    }

    @PutMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update feed comment", description = "Update a comment or reply owned by the authenticated author.")
    public ResponseEntity<ApiResponse<FeedPostCommentResponseDTO>> updateComment(
            @PathVariable String commentId, @Valid @RequestBody FeedPostCommentCreateDTO request) {
        return ResponseEntity.ok(ApiResponse.success(
                feedEngagementService.updateComment(commentId, request), "Comment updated."));
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> deleteComment(@PathVariable String commentId) {
        feedEngagementService.deleteComment(commentId);
        return ResponseEntity.ok(ApiResponse.success(null, "Comment removed."));
    }

    @PostMapping("/comments/{commentId}/likes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> likeComment(@PathVariable String commentId) {
        feedEngagementService.likeComment(commentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(null, "Comment liked."));
    }

    @DeleteMapping("/comments/{commentId}/likes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> unlikeComment(@PathVariable String commentId) {
        feedEngagementService.unlikeComment(commentId);
        return ResponseEntity.ok(ApiResponse.success(null, "Comment unliked."));
    }

    @GetMapping("/comments/{commentId}/likes")
    public ResponseEntity<ApiResponse<FeedPostLikeStatusDTO>> commentLikeStatus(@PathVariable String commentId) {
        return ResponseEntity.ok(ApiResponse.success(feedEngagementService.commentLikeStatus(commentId)));
    }

}
