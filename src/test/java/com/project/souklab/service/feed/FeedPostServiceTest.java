package com.project.souklab.service.feed;
import java.io.ByteArrayInputStream;

import com.project.souklab.dto.feed.FeedPostModerationDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.NotificationType;
import com.project.souklab.security.Permission;

import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.FeedPostRepository;
import com.project.souklab.dao.FeedPostLikeRepository;
import com.project.souklab.dao.FeedPostBookmarkRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.config.FeedProperties;
import com.project.souklab.dto.feed.FeedPostCreateDTO;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.lifecycle.StorageObjectLifecycle;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.FeedPostType;
import com.project.souklab.model.FeedPost;
import com.project.souklab.model.FeedPostMedia;
import com.project.souklab.model.User;
import com.project.souklab.model.Formation;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.security.AccessControlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies feed author eligibility and pending moderation creation.
 */
@ExtendWith(MockitoExtension.class)
class FeedPostServiceTest {

    @Mock private FeedPostRepository postRepository;
    @Mock private FeedPostLikeRepository postLikeRepository;
    @Mock private FeedPostBookmarkRepository bookmarkRepository;
    @Mock private FormationRepository formationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ArtisanRepository artisanRepository;
    @Mock private FileValidator fileValidator;
    @Mock private VirusScanService virusScanService;
    @Mock private StorageService storageService;
    @Mock private FileUrlResolver fileUrlResolver;
    @Mock private NotificationService notificationService;
    @Mock private StorageObjectLifecycle storageObjectLifecycle;
    @Mock private AccessControlService accessControlService;
    @Spy private FeedProperties feedProperties = feedProperties();
    @Mock private Clock clock;
    @Mock private FeedPrivacyService feedPrivacyService;

    @InjectMocks private FeedPostService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().email("artisan@example.com").status(AccountStatus.ACTIVE).build();
        user.setId("user-1");
        Artisan artisan = Artisan.builder().id("artisan-1").user(user).isVerified(true).build();
        user.setArtisan(artisan);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "artisan@example.com", "credentials", List.of(Permission.Artisan.CONTENT)));
        lenient().when(userRepository.findByEmail("artisan@example.com")).thenReturn(Optional.of(user));
        lenient().when(accessControlService.isAdmin(any())).thenReturn(false);
        lenient().when(accessControlService.canManageArtisanContent(any())).thenReturn(true);
        lenient().when(feedPrivacyService.protectPost(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static FeedProperties feedProperties() {
        FeedProperties feed = new FeedProperties();
        feed.setMaxMediaPerPost(10);
        feed.setAllowedImageMimeTypes(List.of("image/jpeg", "image/png", "image/webp"));
        return feed;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verifiedArtisanCreatesPendingPost() {
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FeedPostCreateDTO request = new FeedPostCreateDTO(FeedPostType.ACTUALITE, "Title", "Body", null);

        var response = service.create(request);

        assertThat(response.getStatus()).isEqualTo(FeedPostStatus.PENDING);
        assertThat(response.getTitle()).isEqualTo("Title");
    }

    @Test
    void unverifiedArtisanIsRejected() {
        user.getArtisan().setVerified(false);
        FeedPostCreateDTO request = new FeedPostCreateDTO(FeedPostType.ACTUALITE, "Title", "Body", null);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listsAndGetsOnlyPublishedPosts() {
        FeedPost post = post(FeedPostStatus.PUBLISHED);
        when(postRepository.findByStatusAndDeletedAtIsNull(eq(FeedPostStatus.PUBLISHED), any())).thenReturn(new PageImpl<>(List.of(post)));
        when(postRepository.findByStatusAndTypeAndDeletedAtIsNull(eq(FeedPostStatus.PUBLISHED), eq(FeedPostType.ACTUALITE), any())).thenReturn(new PageImpl<>(List.of(post)));
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        assertThat(service.listPublic(null, PageRequest.of(0, 10))).hasSize(1);
        assertThat(service.listPublic(FeedPostType.ACTUALITE, PageRequest.of(0, 10))).hasSize(1);
        assertThat(service.getPublic("p1").getStatus()).isEqualTo(FeedPostStatus.PUBLISHED);
        post.setStatus(FeedPostStatus.HIDDEN);
        assertThatThrownBy(() -> service.getPublic("p1")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validatesFormationReferencesOnCreate() {
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(formationRepository.findByIdAndDeletedAtIsNull("f1")).thenReturn(Optional.of(new Formation()));
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(FeedPostType.FORMATION, "t", "b", null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(FeedPostType.ACTUALITE, "t", "b", "f1")))
                .isInstanceOf(BadRequestException.class);
        assertThat(service.create(new FeedPostCreateDTO(FeedPostType.FORMATION, " t ", " b ", "f1"))).isNotNull();
    }

    @Test
    void updatesAndRemovesOwnedPost() {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.update("p1", new FeedPostCreateDTO(FeedPostType.ACTUALITE, "new", "body", null))).isNotNull();
        service.remove("p1");
        assertThat(post.getStatus()).isEqualTo(FeedPostStatus.REMOVED);
        verify(storageObjectLifecycle, never()).deleteAfterCommit(any());
    }

    @Test
    void updateValidatesFormationReferenceAndAdminCanPreservePublishedState() {
        FeedPost post = post(FeedPostStatus.PUBLISHED);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(formationRepository.findByIdAndDeletedAtIsNull("f1")).thenReturn(Optional.of(new Formation()));

        assertThatThrownBy(() -> service.update("p1", new FeedPostCreateDTO(
                FeedPostType.FORMATION, "title", "body", null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update("p1", new FeedPostCreateDTO(
                FeedPostType.ACTUALITE, "title", "body", "f1")))
                .isInstanceOf(BadRequestException.class);

        when(accessControlService.canModerateFeed(any())).thenReturn(true);
        assertThat(service.update("p1", new FeedPostCreateDTO(
                FeedPostType.ACTUALITE, "new title", "new body", null))).isNotNull();
        assertThat(post.getStatus()).isEqualTo(FeedPostStatus.PUBLISHED);
    }

    @Test
    void removeSchedulesCleanupForEveryAttachedMedia() {
        FeedPost post = post(FeedPostStatus.PUBLISHED);
        FeedPostMedia first = FeedPostMedia.builder().storageKey("first").build();
        FeedPostMedia second = FeedPostMedia.builder().storageKey("second").build();
        post.addMedia(first);
        post.addMedia(second);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.remove("p1");

        verify(storageObjectLifecycle).deleteAfterCommit("first");
        verify(storageObjectLifecycle).deleteAfterCommit("second");
    }

    @Test
    void deniesForeignPostAndAdminModerates() {
        FeedPost post = post(FeedPostStatus.PENDING);
        User other = User.builder().email("other@test").build(); other.setId("other"); post.setAuthor(other);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        assertThatThrownBy(() -> service.update("p1", new FeedPostCreateDTO(FeedPostType.ACTUALITE, "t", "b", null)))
                .isInstanceOf(ForbiddenException.class);
        when(accessControlService.canModerateFeed(any())).thenReturn(true);
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.publish("p1", new FeedPostModerationDTO(" note "))).isNotNull();
        assertThat(post.getStatus()).isEqualTo(FeedPostStatus.PUBLISHED);
    }

    @Test
    void handlesMediaValidationAndRemoval() throws Exception {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1});
        ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(new byte[]{1}), "image.jpg", "image/jpeg", 1);
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validated);
        when(virusScanService.scan(validated)).thenReturn(validated);
        when(storageService.store(any(), any(), any(), anyLong())).thenReturn(new StorageResult("key", "image.jpg", "image/jpeg", 1, Instant.now()));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fileUrlResolver.toUrl("key")).thenReturn("/files/key");
        assertThat(service.addMedia("p1", file).getUrl()).isEqualTo("/files/key");
        post.getMedia().get(0).setId("media-1");
        service.removeMedia("p1", "media-1");
        verify(storageObjectLifecycle).deleteAfterCommit("key");
    }

    @Test
    void rejectsUnreadableMediaUpload() throws Exception {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        doThrow(new IOException("cannot read")).when(file).getInputStream();

        assertThatThrownBy(() -> service.addMedia("p1", file))
                .isInstanceOf(BadRequestException.class);
        verifyNoMediaPersistence();
    }

    @Test
    void deletesStoredMediaWhenDatabasePersistenceFails() throws Exception {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        MockMultipartFile file = new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1});
        ValidatedFile validated = new ValidatedFile(new ByteArrayInputStream(new byte[]{1}),
                "image.jpg", "image/jpeg", 1);
        when(fileValidator.validateAndSanitize(any(), any(), any(), anyLong(), any())).thenReturn(validated);
        when(virusScanService.scan(validated)).thenReturn(validated);
        when(storageService.store(any(), any(), any(), anyLong()))
                .thenReturn(new StorageResult("stored-key", "image.jpg", "image/jpeg", 1, Instant.now()));
        doThrow(new IllegalStateException("database unavailable")).when(postRepository).save(any());

        assertThatThrownBy(() -> service.addMedia("p1", file))
                .isInstanceOf(IllegalStateException.class);
        verify(storageService).delete("stored-key");
    }

    @Test
    void rejectsInactiveAndUnauthorizedAuthorsAndMissingFormation() {
        user.setStatus(AccountStatus.SUSPENDED);
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(FeedPostType.ACTUALITE, "t", "b", null)))
                .isInstanceOf(ForbiddenException.class);
        user.setStatus(AccountStatus.ACTIVE);
        when(accessControlService.canManageArtisanContent(any())).thenReturn(false);
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(FeedPostType.ACTUALITE, "t", "b", null)))
                .isInstanceOf(ForbiddenException.class);
        when(accessControlService.canManageArtisanContent(any())).thenReturn(true);
        when(formationRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(FeedPostType.FORMATION, "t", "b", "missing")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void enforcesMediaGuardsAndMissingMedia() {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        assertThatThrownBy(() -> service.addMedia("p1", null)).isInstanceOf(BadRequestException.class);
        for (int i = 0; i < 10; i++) {
            FeedPostMedia media = FeedPostMedia.builder().storageKey("key-" + i).build();
            media.setId("media-" + i);
            post.addMedia(media);
        }
        assertThatThrownBy(() -> service.addMedia("p1", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1})))
                .isInstanceOf(BadRequestException.class);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post(FeedPostStatus.PENDING)));
        assertThatThrownBy(() -> service.removeMedia("p1", "missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void moderatesRemovedPostsAndListsPendingAsAdmin() {
        FeedPost removed = post(FeedPostStatus.REMOVED);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(removed));
        when(accessControlService.canModerateFeed(any())).thenReturn(true);
        assertThatThrownBy(() -> service.publish("p1", new FeedPostModerationDTO("note")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.hide("p1", new FeedPostModerationDTO("note")))
                .isInstanceOf(ConflictException.class);
        when(postRepository.findByStatusAndDeletedAtIsNull(eq(FeedPostStatus.PENDING), any())).thenReturn(new PageImpl<>(List.of(post(FeedPostStatus.PENDING))));
        assertThat(service.listPending(PageRequest.of(0, 10))).hasSize(1);
    }

    @Test
    void publishesFormationAndNotifiesItsAuthor() {
        FeedPost post = post(FeedPostStatus.PENDING);
        post.setType(FeedPostType.FORMATION);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accessControlService.canModerateFeed(any())).thenReturn(true);

        service.publish("p1", new FeedPostModerationDTO("note"));

        verify(notificationService).createForUser(user, "Your feed post was published.",
                NotificationType.Feed.PUBLISHED, "p1");
    }

    @Test
    void hidesNonRemovedPostAndRejectsNonAdminModerationAccess() {
        FeedPost post = post(FeedPostStatus.PENDING);
        when(postRepository.findByIdAndDeletedAtIsNull("p1")).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accessControlService.canModerateFeed(any())).thenReturn(true);

        assertThat(service.hide("p1", new FeedPostModerationDTO(" note ")))
                .isNotNull();
        assertThat(post.getStatus()).isEqualTo(FeedPostStatus.HIDDEN);

        when(accessControlService.canModerateFeed(any())).thenReturn(false);
        assertThatThrownBy(() -> service.listPending(PageRequest.of(0, 10)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsMissingPostAndMissingCurrentUser() {
        when(postRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublic("missing"))
                .isInstanceOf(ResourceNotFoundException.class);

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.create(new FeedPostCreateDTO(
                FeedPostType.ACTUALITE, "title", "body", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    private void verifyNoMediaPersistence() {
        verify(fileValidator, never()).validateAndSanitize(any(), any(), any(), anyLong(), any());
        verify(storageService, never()).store(any(), any(), any(), anyLong());
    }

    private FeedPost post(FeedPostStatus status) {
        FeedPost post = FeedPost.builder().author(user).type(FeedPostType.ACTUALITE).title("title").body("body").status(status).build();
        post.setId("p1");
        return post;
    }
}
