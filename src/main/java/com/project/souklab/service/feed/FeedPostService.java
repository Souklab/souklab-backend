package com.project.souklab.service.feed;

import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.analytics.AnalyticsEvent;
import com.project.souklab.analytics.AnalyticsMetadata;

import com.project.souklab.analytics.ActivityEventService;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dao.FeedTagRepository;
import com.project.souklab.dao.FeedPostBookmarkRepository;
import com.project.souklab.dao.FeedPostLikeRepository;
import com.project.souklab.config.FeedProperties;
import com.project.souklab.dto.feed.FeedPostCreateDTO;
import com.project.souklab.dto.feed.FeedPostMediaResponseDTO;
import com.project.souklab.dto.feed.FeedPostModerationDTO;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.lifecycle.StorageObjectLifecycle;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.FeedPost;
import com.project.souklab.model.FeedPostMedia;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.FeedPostType;
import com.project.souklab.model.FeedTag;
import com.project.souklab.model.Formation;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.security.AccessControlService;
import com.project.souklab.security.Permission;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Coordinates moderated public feed posts and their stored image attachments.
 */
@Service
@RequiredArgsConstructor
public class FeedPostService {

    private final FeedPostRepository postRepository;
    private final FormationRepository formationRepository;
    private final UserRepository userRepository;
    private final FeedTagRepository tagRepository;
    private final FeedPostLikeRepository postLikeRepository;
    private final FeedPostBookmarkRepository bookmarkRepository;
    private final FileValidator fileValidator;
    private final VirusScanService virusScanService;
    private final StorageService storageService;
    private final FileUrlResolver fileUrlResolver;
    private final NotificationService notificationService;
    private final StorageObjectLifecycle storageObjectLifecycle;
    private final AccessControlService accessControlService;
    private final Clock clock;
    private final FeedProperties feedProperties;
    private final FeedPrivacyService feedPrivacyService;
    private ActivityEventService activityEventService;

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService value) { this.activityEventService = value; }

    /**
     * Lists public published posts.
     *
     * @param type optional post type
     * @param pageable pagination and sorting
     * @return published posts
     */
    @Transactional(readOnly = true)
    public Page<FeedPostResponseDTO> listPublic(FeedPostType type, Pageable pageable) {
        Pageable effectivePageable = pageable.getSort().isSorted() ? pageable : PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<FeedPost> posts = type == null
                ? postRepository.findByStatusAndDeletedAtIsNull(FeedPostStatus.PUBLISHED, effectivePageable)
                : postRepository.findByStatusAndTypeAndDeletedAtIsNull(FeedPostStatus.PUBLISHED, type, effectivePageable);
        return toResponsePage(posts);
    }

    /**
     * Finds a published post for public consumption.
     *
     * @param id post identifier
     * @return post response
     */
    @Transactional(readOnly = true)
    public FeedPostResponseDTO getPublic(String id) {
        FeedPost post = findPost(id);
        if (post.getStatus() != FeedPostStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Feed post not found.");
        }
        return toResponse(post);
    }

    /**
     * Submits a post for moderation.
     *
     * @param request post payload
     * @return pending post response
     */
    @Transactional
    public FeedPostResponseDTO create(FeedPostCreateDTO request) {
        User author = currentUser();
        validateAuthor(author);
        Formation formation = resolveFormation(request.getFormationId());
        if (request.getType() == FeedPostType.FORMATION && formation == null) {
            throw new BadRequestException("Formation posts require a formationId.");
        }
        if (request.getType() != FeedPostType.FORMATION && formation != null) {
            throw new BadRequestException("Only formation posts may reference a formation.");
        }
        validateTextLengths(request);
        FeedPost post = FeedPost.builder()
                .author(author)
                .type(request.getType())
                .title(request.getTitle().trim())
                .body(request.getBody().trim())
                .formation(formation)
                .status(request.isDraft() ? FeedPostStatus.DRAFT : FeedPostStatus.PENDING)
                .build();
        post.setTags(resolveTags(request.getTags()));
        FeedPost saved = postRepository.save(post);
        if (saved.getStatus() == FeedPostStatus.PENDING) {
            notificationService.notifyPermissionHolders(Permission.Admin.FEED.value(), "New feed post submitted for moderation: " + saved.getTitle(),
                    NotificationType.Feed.SUBMITTED, saved.getId());
        }
        return toResponse(saved);
    }

    /**
     * Updates a post owned by the current author or an administrator.
     *
     * @param id post identifier
     * @param request new post content
     * @return updated post
     */
    @Transactional
    public FeedPostResponseDTO update(String id, FeedPostCreateDTO request) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        Formation formation = resolveFormation(request.getFormationId());
        if (request.getType() == FeedPostType.FORMATION && formation == null) {
            throw new BadRequestException("Formation posts require a formationId.");
        }
        if (request.getType() != FeedPostType.FORMATION && formation != null) {
            throw new BadRequestException("Only formation posts may reference a formation.");
        }
        validateTextLengths(request);
        post.setType(request.getType());
        post.setTitle(request.getTitle().trim());
        post.setBody(request.getBody().trim());
        post.setFormation(formation);
        if (!isAdmin()) {
            FeedPostStatus previous = post.getStatus();
            post.setStatus(previous == FeedPostStatus.DRAFT || previous == FeedPostStatus.REJECTED ? previous : FeedPostStatus.PENDING);
            if (previous != post.getStatus()) {
                post.setModeratedBy(null);
                notificationService.notifyPermissionHolders(Permission.Admin.FEED.value(),
                        "Feed post resubmitted for moderation: " + post.getTitle(), NotificationType.Feed.SUBMITTED, post.getId());
            }
            if (post.getStatus() != FeedPostStatus.PUBLISHED) {
                post.setPublishedAt(null);
            }
        }
        post.setTags(resolveTags(request.getTags()));
        return toResponse(postRepository.save(post));
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<FeedPostResponseDTO> listMine(FeedPostStatus status, Pageable pageable) {
        User user = currentUser();
        validateAuthor(user);
        Page<FeedPost> posts = status == null
                ? postRepository.findByAuthorIdAndDeletedAtIsNull(user.getId(), pageable)
                : postRepository.findByAuthorIdAndStatusAndDeletedAtIsNull(user.getId(), status, pageable);
        return PaginatedResponse.from(toResponsePage(posts));
    }

    @Transactional(readOnly = true)
    public FeedPostResponseDTO getForCaller(String id) {
        FeedPost post = findPost(id);
        User user = currentUser();
        if (!post.getAuthor().getId().equals(user.getId()) && !isAdmin()
                && post.getStatus() != FeedPostStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Feed post not found.");
        }
        if (post.getStatus() != FeedPostStatus.PUBLISHED && !post.getAuthor().getId().equals(user.getId()) && !isAdmin()) {
            throw new ResourceNotFoundException("Feed post not found.");
        }
        return toResponse(post);
    }

    @Transactional
    public FeedPostResponseDTO submit(String id) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        if (post.getStatus() != FeedPostStatus.DRAFT && post.getStatus() != FeedPostStatus.REJECTED) {
            throw new ConflictException("Only draft or rejected posts may be submitted.");
        }
        post.setStatus(FeedPostStatus.PENDING);
        post.setPublishedAt(null);
        FeedPost saved = postRepository.save(post);
        notificationService.notifyPermissionHolders(Permission.Admin.FEED.value(), "Feed post resubmitted for moderation: " + saved.getTitle(),
                NotificationType.Feed.SUBMITTED, saved.getId());
        return toResponse(saved);
    }

    /**
     * Soft-removes an owned or administrator-managed post.
     *
     * @param id post identifier
     */
    @Transactional
    public void remove(String id) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        post.setStatus(FeedPostStatus.REMOVED);
        post.setDeletedAt(LocalDateTime.now(clock));
        postRepository.save(post);
        post.getMedia().forEach(media -> storageObjectLifecycle.deleteAfterCommit(media.getStorageKey()));
    }

    /**
     * Adds an image attachment to an owned post.
     *
     * @param id post identifier
     * @param file image payload
     * @return stored media response
     */
    @Transactional
    public FeedPostMediaResponseDTO addMedia(String id, MultipartFile file) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        int maxMediaPerPost = feedProperties.getMaxMediaPerPost();
        if (post.getMedia().size() >= maxMediaPerPost) {
            throw new BadRequestException("A feed post may contain at most " + maxMediaPerPost + " images.");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Media file is required.");
        }
        String storedKey = null;
        try {
            ValidatedFile validated = fileValidator.validateAndSanitize(
                    file.getInputStream(), file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    feedProperties.getAllowedImageMimeTypes());
            ValidatedFile scanned = virusScanService.scan(validated);
            StorageResult stored = storageService.store(scanned.content(), scanned.sanitizedFilename(), scanned.detectedMimeType(), scanned.size());
            storedKey = stored.key();
            FeedPostMedia media = FeedPostMedia.builder()
                    .post(post)
                    .storageKey(storedKey)
                    .contentType(scanned.detectedMimeType())
                    .fileSize(scanned.size())
                    .displayOrder(post.getMedia().size())
                    .build();
            media.ensureId();
            post.addMedia(media);
            remoderateAfterMutation(post);
            postRepository.save(post);
            return FeedPostMediaResponseDTO.builder()
                    .id(media.getId())
                    .url(fileUrlResolver.toUrl(media.getStorageKey()))
                    .contentType(media.getContentType())
                    .displayOrder(media.getDisplayOrder())
                    .build();
        } catch (IOException exception) {
            throw new BadRequestException("Unable to read media upload.");
        } catch (RuntimeException exception) {
            if (storedKey != null) {
                storageService.delete(storedKey);
            }
            throw exception;
        }
    }

    /**
     * Removes an attachment from an owned post and schedules its object cleanup.
     *
     * @param id post identifier
     * @param mediaId attachment identifier
     */
    @Transactional
    public void removeMedia(String id, String mediaId) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        FeedPostMedia media = post.getMedia().stream()
                .filter(candidate -> candidate.getId().equals(mediaId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Feed media not found."));
        post.removeMedia(media);
        remoderateAfterMutation(post);
        postRepository.save(post);
        storageObjectLifecycle.deleteAfterCommit(media.getStorageKey());
    }

    private void remoderateAfterMutation(FeedPost post) {
        if (post.getStatus() == FeedPostStatus.PUBLISHED) {
            post.setStatus(FeedPostStatus.PENDING);
            post.setPublishedAt(null);
            post.setModeratedBy(null);
            notificationService.notifyPermissionHolders(Permission.Admin.FEED.value(),
                    "Published feed post media changed and requires moderation: " + post.getTitle(),
                    NotificationType.Feed.SUBMITTED, post.getId());
        }
    }

    @Transactional
    public void reorderMedia(String id, List<String> mediaIds) {
        FeedPost post = findPost(id);
        requireAuthorOrAdmin(post);
        if (mediaIds == null || mediaIds.size() != post.getMedia().size()
                || mediaIds.stream().distinct().count() != mediaIds.size()
                || post.getMedia().stream().map(FeedPostMedia::getId).anyMatch(mediaId -> !mediaIds.contains(mediaId))) {
            throw new BadRequestException("Media order must contain each post attachment exactly once.");
        }
        for (int i = 0; i < mediaIds.size(); i++) {
            final int order = i;
            post.getMedia().stream().filter(media -> media.getId().equals(mediaIds.get(order))).findFirst().orElseThrow().setDisplayOrder(order);
        }
        remoderateAfterMutation(post);
        postRepository.save(post);
    }

    /**
     * Publishes a pending post as an administrator.
     *
     * @param id post identifier
     * @param request moderation note
     * @return published post
     */
    @Transactional
    public FeedPostResponseDTO publish(String id, FeedPostModerationDTO request) {
        requireAdmin();
        FeedPost post = findPost(id);
        if (post.getStatus() == FeedPostStatus.REMOVED) {
            throw new ConflictException("Removed posts cannot be published.");
        }
        post.setModeratedBy(currentUser());
        post.setModerationNote(request.getNote().trim());
        post.setStatus(FeedPostStatus.PUBLISHED);
        post.setPublishedAt(LocalDateTime.now(clock));
        FeedPost saved = postRepository.save(post);
        if (activityEventService != null) {
            User moderator = currentUser();
            activityEventService.record(AnalyticsEvent.Feed.Post.PUBLISHED, moderator.getId(), saved.getId(),
                    Map.of(AnalyticsMetadata.Content.Post.TYPE, saved.getType()));
        }
        // A formation-shaped feed post is still a feed publication. NEW_FORMATION is
        // reserved for the formation catalog lifecycle, not feed moderation.
        notificationService.createForUser(saved.getAuthor(), "Your feed post was published.",
                NotificationType.Feed.PUBLISHED, saved.getId());
        return toResponse(saved);
    }

    /**
     * Hides a post as an administrator.
     *
     * @param id post identifier
     * @param request moderation note
     * @return hidden post
     */
    @Transactional
    public FeedPostResponseDTO hide(String id, FeedPostModerationDTO request) {
        requireAdmin();
        FeedPost post = findPost(id);
        if (post.getStatus() == FeedPostStatus.REMOVED) {
            throw new ConflictException("Removed posts cannot be hidden.");
        }
        post.setStatus(FeedPostStatus.HIDDEN);
        post.setModeratedBy(currentUser());
        post.setModerationNote(request.getNote().trim());
        FeedPost saved = postRepository.save(post);
        notificationService.createForUser(saved.getAuthor(), "Your feed post was hidden: " + saved.getModerationNote(),
                NotificationType.Feed.HIDDEN, saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public FeedPostResponseDTO reject(String id, FeedPostModerationDTO request) {
        requireAdmin();
        FeedPost post = findPost(id);
        if (post.getStatus() == FeedPostStatus.REMOVED) {
            throw new ConflictException("Removed posts cannot be rejected.");
        }
        post.setStatus(FeedPostStatus.REJECTED);
        post.setModeratedBy(currentUser());
        post.setModerationNote(request.getNote().trim());
        FeedPost saved = postRepository.save(post);
        notificationService.createForUser(saved.getAuthor(), "Your feed post was rejected: " + saved.getModerationNote(),
                NotificationType.Feed.REJECTED, saved.getId());
        return toResponse(saved);
    }

    /**
     * Lists pending moderation items.
     *
     * @param pageable pagination and sorting
     * @return pending posts
     */
    @Transactional(readOnly = true)
    public Page<FeedPostResponseDTO> listPending(Pageable pageable) {
        requireAdmin();
        return toResponsePage(postRepository.findByStatusAndDeletedAtIsNull(FeedPostStatus.PENDING, pageable));
    }

    private FeedPost findPost(String id) {
        return postRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Feed post not found."));
    }

    private Formation resolveFormation(String id) {
        return id == null || id.isBlank() ? null : formationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Formation not found."));
    }

    private Page<FeedPostResponseDTO> toResponsePage(Page<FeedPost> posts) {
        if (posts.isEmpty()) {
            return posts.map(this::toResponse);
        }
        User viewer = resolveCurrentViewer();
        if (viewer == null) {
            return posts.map(post -> {
                FeedPostResponseDTO mapped = FeedPostResponseDTO.from(post, this::resolveMediaUrl);
                return feedPrivacyService.protectPost(post, mapped);
            });
        }
        List<String> postIds = posts.getContent().stream().map(FeedPost::getId).toList();
        Set<String> likedPostIds = postLikeRepository.findLikedPostIdsByUserIdAndPostIdIn(viewer.getId(), postIds);
        Set<String> bookmarkedPostIds = bookmarkRepository.findBookmarkedPostIdsByUserIdAndPostIdIn(viewer.getId(), postIds);
        return posts.map(post -> toResponseWithViewer(post, viewer, likedPostIds, bookmarkedPostIds));
    }

    private FeedPostResponseDTO toResponse(FeedPost post) {
        FeedPostResponseDTO mappedResponse = FeedPostResponseDTO.from(post, this::resolveMediaUrl);
        FeedPostResponseDTO response = feedPrivacyService.protectPost(post, mappedResponse);
        User viewer = resolveCurrentViewer();
        if (viewer == null) {
            return response;
        }
        return response.toBuilder()
                .likedByCurrentUser(postLikeRepository.existsByPostIdAndUserId(post.getId(), viewer.getId()))
                .bookmarkedByCurrentUser(bookmarkRepository.existsByPostIdAndUserId(post.getId(), viewer.getId()))
                .build();
    }

    private FeedPostResponseDTO toResponseWithViewer(FeedPost post, User viewer, Set<String> likedPostIds, Set<String> bookmarkedPostIds) {
        FeedPostResponseDTO mappedResponse = FeedPostResponseDTO.from(post, this::resolveMediaUrl);
        FeedPostResponseDTO response = feedPrivacyService.protectPost(post, mappedResponse);
        if (viewer == null) {
            return response;
        }
        return response.toBuilder()
                .likedByCurrentUser(likedPostIds != null && likedPostIds.contains(post.getId()))
                .bookmarkedByCurrentUser(bookmarkedPostIds != null && bookmarkedPostIds.contains(post.getId()))
                .build();
    }

    private String resolveMediaUrl(String key) {
        return fileUrlResolver == null ? key : fileUrlResolver.toUrl(key);
    }

    private User resolveCurrentViewer() {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            return null;
        }
        return userRepository.findByEmail(email).orElse(null);
    }

    private List<FeedTag> resolveTags(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        int maxTags = feedProperties.getMaxTagsPerPost();
        if (values.size() > maxTags) {
            throw new BadRequestException("A feed post contains too many tags.");
        }
        Map<String, String> normalized = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toMap(value -> value.toLowerCase(Locale.ROOT), value -> value,
                        (first, ignored) -> first, LinkedHashMap::new));
        if (normalized.values().stream().anyMatch(value -> value.length() > feedProperties.getMaxTagLength())) {
            throw new BadRequestException("A feed tag is too long.");
        }
        List<FeedTag> existing = tagRepository.findBySlugIn(normalized.keySet());
        Map<String, FeedTag> bySlug = existing.stream().collect(Collectors.toMap(FeedTag::getSlug, value -> value));
        return normalized.entrySet().stream().map(entry -> bySlug.computeIfAbsent(entry.getKey(), slug ->
                tagRepository.save(FeedTag.builder().slug(slug).name(entry.getValue()).build())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void validateTextLengths(FeedPostCreateDTO request) {
        int maxTitle = feedProperties.getMaxTitleLength();
        int maxBody = feedProperties.getMaxBodyLength();
        if (maxTitle > 0 && request.getTitle().trim().length() > maxTitle) {
            throw new BadRequestException("Feed post title exceeds the configured maximum length.");
        }
        if (maxBody > 0 && request.getBody().trim().length() > maxBody) {
            throw new BadRequestException("Feed post body exceeds the configured maximum length.");
        }
    }

    private User currentUser() {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new ForbiddenException("Authentication is required.");
        }
        return userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private void validateAuthor(User user) {
        boolean isActive = user.getStatus() == AccountStatus.ACTIVE;
        boolean isEligibleArtisan = hasArtisanContentPermission() && isVerifiedArtisan(user);
        boolean isPermitted = isActive && (isAdmin() || isEligibleArtisan);
        if (!isPermitted) {
            throw new ForbiddenException("Only active verified artisans or administrators may publish feed posts.");
        }
    }

    private boolean hasArtisanContentPermission() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return accessControlService.canManageArtisanContent(authentication);
    }

    private boolean isVerifiedArtisan(User user) {
        return user.getArtisan() != null && user.getArtisan().isVerified();
    }

    private void requireAuthorOrAdmin(FeedPost post) {
        if (isAdmin()) {
            return;
        }
        User user = currentUser();
        if (!post.getAuthor().getId().equals(user.getId())) {
            throw new ForbiddenException("You may only manage your own feed posts.");
        }
    }

    private void requireAdmin() {
        if (!isAdmin()) {
            throw new ForbiddenException("Administrator access is required.");
        }
    }

    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return accessControlService.canModerateFeed(authentication);
    }
}
