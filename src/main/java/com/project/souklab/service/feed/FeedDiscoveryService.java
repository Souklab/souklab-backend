package com.project.souklab.service.feed;

import com.project.souklab.config.SearchProperties;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.FeedPostBookmarkRepository;
import com.project.souklab.dao.FeedPostLikeRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.model.FeedPost;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.FeedPostType;
import com.project.souklab.model.User;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.hibernate.search.engine.search.query.SearchResult;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageImpl;

@Service
@RequiredArgsConstructor
public class FeedDiscoveryService {
    private final FeedPostRepository postRepository;
    private final UserRepository userRepository;
    private final FeedPostLikeRepository postLikeRepository;
    private final FeedPostBookmarkRepository bookmarkRepository;
    private final SearchProperties searchProperties;
    private final EntityManager entityManager;
    private final FeedPrivacyService feedPrivacyService;
    private final FileUrlResolver fileUrlResolver;

    @Transactional(readOnly = true)
    public PaginatedResponse<FeedPostResponseDTO> list(FeedPostType type, String authorId, String tag,
                                                        String query, String sort, Pageable pageable) {
        Pageable effectivePageable = applySort(pageable, sort);
        Page<FeedPost> page = searchEnabled(query, type, authorId, tag)
                ? search(query, effectivePageable)
                : postRepository.findForDiscovery(FeedPostStatus.PUBLISHED, type, authorId,
                normalize(tag), normalize(query), effectivePageable);
        return PaginatedResponse.from(toResponsePage(page));
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<FeedPostResponseDTO> following(Pageable pageable) {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            throw new ForbiddenException("Authentication is required.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ForbiddenException("Authentication is required."));
        if (user.getClient() == null) {
            return PaginatedResponse.<FeedPostResponseDTO>builder()
                    .content(List.of()).pageNumber(pageable.getPageNumber()).pageSize(pageable.getPageSize())
                    .totalElements(0).totalPages(0).last(true).build();
        }
        Page<FeedPost> page = postRepository.findFollowing(user.getClient().getId(), FeedPostStatus.PUBLISHED, pageable);
        return PaginatedResponse.from(toResponsePage(page, user));
    }

    private Page<FeedPostResponseDTO> toResponsePage(Page<FeedPost> posts) {
        return toResponsePage(posts, resolveCurrentViewer());
    }

    private Page<FeedPostResponseDTO> toResponsePage(Page<FeedPost> posts, User viewer) {
        if (posts.isEmpty()) {
            return posts.map(this::toResponse);
        }
        if (viewer == null) {
            return posts.map(post -> {
                FeedPostResponseDTO mapped = FeedPostResponseDTO.from(post, fileUrlResolver::toUrl);
                return feedPrivacyService.protectPost(post, mapped);
            });
        }
        List<String> postIds = posts.getContent().stream().map(FeedPost::getId).toList();
        Set<String> likedPostIds = postLikeRepository.findLikedPostIdsByUserIdAndPostIdIn(viewer.getId(), postIds);
        Set<String> bookmarkedPostIds = bookmarkRepository.findBookmarkedPostIdsByUserIdAndPostIdIn(viewer.getId(), postIds);
        return posts.map(post -> toResponseWithViewer(post, viewer, likedPostIds, bookmarkedPostIds));
    }

    private FeedPostResponseDTO toResponse(FeedPost post) {
        FeedPostResponseDTO mappedResponse = FeedPostResponseDTO.from(post, fileUrlResolver::toUrl);
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
        FeedPostResponseDTO mappedResponse = FeedPostResponseDTO.from(post, fileUrlResolver::toUrl);
        FeedPostResponseDTO response = feedPrivacyService.protectPost(post, mappedResponse);
        if (viewer == null) {
            return response;
        }
        return response.toBuilder()
                .likedByCurrentUser(likedPostIds != null && likedPostIds.contains(post.getId()))
                .bookmarkedByCurrentUser(bookmarkedPostIds != null && bookmarkedPostIds.contains(post.getId()))
                .build();
    }

    private User resolveCurrentViewer() {
        String email = SecurityUtils.getCurrentUsername();
        if (email == null) {
            return null;
        }
        return userRepository.findByEmail(email).orElse(null);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean searchEnabled(String query, FeedPostType type, String authorId, String tag) {
        return query != null && !query.isBlank() && type == null && authorId == null && tag == null
                && searchProperties.isEnabled();
    }

    private Page<FeedPost> search(String query, Pageable pageable) {
        SearchSession session = Search.session(entityManager);
        int offset = pageable.getPageNumber() * pageable.getPageSize();
        SearchResult<FeedPost> result = session.search(FeedPost.class)
                .where(f -> f.bool()
                        .should(f.match().field("title").matching(query))
                        .should(f.match().field("body").matching(query))
                        .should(f.match().field("tags.name").matching(query))
                        .filter(f.match().field("status").matching(FeedPostStatus.PUBLISHED.value()))
                        .filter(f.not(f.exists().field("deletedAt"))))
                .sort(f -> pageable.getSort().getOrderFor("likeCount") != null
                        ? f.field("likeCount").desc()
                        : f.field("publishedAt").desc())
                .fetch(offset, pageable.getPageSize());
        List<FeedPost> content = result.hits();
        return new PageImpl<>(content, pageable, result.total().hitCount());
    }

    private Pageable applySort(Pageable pageable, String sort) {
        if (sort == null || sort.isBlank()) {
            return pageable;
        }
        Sort requested = "popular".equalsIgnoreCase(sort)
                ? Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))
                : Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), requested);
    }
}
