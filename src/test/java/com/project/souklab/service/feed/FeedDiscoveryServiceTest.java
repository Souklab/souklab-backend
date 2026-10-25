package com.project.souklab.service.feed;

import com.project.souklab.config.SearchProperties;
import com.project.souklab.dao.FeedPostBookmarkRepository;
import com.project.souklab.dao.FeedPostLikeRepository;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.model.Client;
import com.project.souklab.model.FeedPost;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.FeedPostType;
import com.project.souklab.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedDiscoveryServiceTest {

    @Mock
    private FeedPostRepository postRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FeedPostLikeRepository postLikeRepository;
    @Mock
    private FeedPostBookmarkRepository bookmarkRepository;
    @Mock
    private SearchProperties searchProperties;
    @Mock
    private EntityManager entityManager;
    @Mock
    private FeedPrivacyService feedPrivacyService;
    @Mock
    private FileUrlResolver fileUrlResolver;

    @InjectMocks
    private FeedDiscoveryService feedDiscoveryService;

    private User user;
    private FeedPost post1;
    private FeedPost post2;

    @BeforeEach
    void setUp() {
        user = User.builder().firstName("John").lastName("Doe").email("user@example.com").build();
        user.setId("user-1");

        post1 = FeedPost.builder()
                .title("Post 1")
                .author(user)
                .status(FeedPostStatus.PUBLISHED)
                .tags(Collections.emptyList())
                .media(Collections.emptyList())
                .build();
        post1.setId("post-1");

        post2 = FeedPost.builder()
                .title("Post 2")
                .author(user)
                .status(FeedPostStatus.PUBLISHED)
                .tags(Collections.emptyList())
                .media(Collections.emptyList())
                .build();
        post2.setId("post-2");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_anonymousUser_mapsWithoutUserBatchLookups() {
        Pageable pageable = PageRequest.of(0, 10);
        when(postRepository.findForDiscovery(eq(FeedPostStatus.PUBLISHED), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(post1, post2), pageable, 2));
        when(feedPrivacyService.protectPost(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        PaginatedResponse<FeedPostResponseDTO> response = feedDiscoveryService.list(
                FeedPostType.ACTUALITE, null, null, null, null, pageable);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getId()).isEqualTo("post-1");
        assertThat(response.getContent().get(0).isLikedByCurrentUser()).isFalse();
        assertThat(response.getContent().get(0).isBookmarkedByCurrentUser()).isFalse();
        verify(postLikeRepository, never()).findLikedPostIdsByUserIdAndPostIdIn(any(), any());
        verify(bookmarkRepository, never()).findBookmarkedPostIdsByUserIdAndPostIdIn(any(), any());
    }

    @Test
    void list_authenticatedUser_batchFetchesLikesAndBookmarks() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        Pageable pageable = PageRequest.of(0, 10);
        when(postRepository.findForDiscovery(eq(FeedPostStatus.PUBLISHED), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(post1, post2), pageable, 2));
        when(postLikeRepository.findLikedPostIdsByUserIdAndPostIdIn(eq("user-1"), eq(List.of("post-1", "post-2"))))
                .thenReturn(Set.of("post-1"));
        when(bookmarkRepository.findBookmarkedPostIdsByUserIdAndPostIdIn(eq("user-1"), eq(List.of("post-1", "post-2"))))
                .thenReturn(Set.of("post-2"));
        when(feedPrivacyService.protectPost(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        PaginatedResponse<FeedPostResponseDTO> response = feedDiscoveryService.list(
                null, null, null, null, null, pageable);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).isLikedByCurrentUser()).isTrue();
        assertThat(response.getContent().get(0).isBookmarkedByCurrentUser()).isFalse();
        assertThat(response.getContent().get(1).isLikedByCurrentUser()).isFalse();
        assertThat(response.getContent().get(1).isBookmarkedByCurrentUser()).isTrue();

        verify(postLikeRepository).findLikedPostIdsByUserIdAndPostIdIn("user-1", List.of("post-1", "post-2"));
        verify(bookmarkRepository).findBookmarkedPostIdsByUserIdAndPostIdIn("user-1", List.of("post-1", "post-2"));
    }

    @Test
    void following_unauthenticated_throwsForbiddenException() {
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> feedDiscoveryService.following(pageable))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Authentication is required.");
    }

    @Test
    void following_userWithoutClient_returnsEmptyPage() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        Pageable pageable = PageRequest.of(0, 10);
        PaginatedResponse<FeedPostResponseDTO> response = feedDiscoveryService.following(pageable);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isEqualTo(0);
        verify(postRepository, never()).findFollowing(any(), any(), any());
    }

    @Test
    void following_userWithClient_returnsPostsWithBatchLikedAndBookmarked() {
        Client client = Client.builder().id("client-1").build();
        user.setClient(client);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "pass", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        Pageable pageable = PageRequest.of(0, 10);
        when(postRepository.findFollowing("client-1", FeedPostStatus.PUBLISHED, pageable))
                .thenReturn(new PageImpl<>(List.of(post1), pageable, 1));
        when(postLikeRepository.findLikedPostIdsByUserIdAndPostIdIn("user-1", List.of("post-1")))
                .thenReturn(Set.of("post-1"));
        when(bookmarkRepository.findBookmarkedPostIdsByUserIdAndPostIdIn("user-1", List.of("post-1")))
                .thenReturn(Collections.emptySet());
        when(feedPrivacyService.protectPost(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        PaginatedResponse<FeedPostResponseDTO> response = feedDiscoveryService.following(pageable);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getId()).isEqualTo("post-1");
        assertThat(response.getContent().get(0).isLikedByCurrentUser()).isTrue();
        assertThat(response.getContent().get(0).isBookmarkedByCurrentUser()).isFalse();
    }
}
