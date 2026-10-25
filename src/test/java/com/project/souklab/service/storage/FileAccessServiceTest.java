package com.project.souklab.service.storage;

import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;

import com.project.souklab.dao.ArtisanCertificationRepository;
import com.project.souklab.dao.ArtisanGalleryImageRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.FeedPostMediaRepository;
import com.project.souklab.dao.FormationEnrollmentRepository;
import com.project.souklab.dao.FormationFileRepository;
import com.project.souklab.dao.FormationRepository;
import com.project.souklab.dao.MessageAttachmentRepository;
import com.project.souklab.dao.UserAvatarRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanCertification;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.EnrollmentStatus;
import com.project.souklab.model.FeedPost;
import com.project.souklab.model.FeedPostMedia;
import com.project.souklab.model.FeedPostStatus;
import com.project.souklab.model.Formation;
import com.project.souklab.model.FormationFile;
import com.project.souklab.model.MessageAttachment;
import com.project.souklab.model.User;
import com.project.souklab.model.UserAvatar;
import com.project.souklab.security.AccessControlService;
import com.project.souklab.security.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileAccessServiceTest {
    @Mock UserAvatarRepository userAvatarRepository;
    @Mock ArtisanGalleryImageRepository galleryImageRepository;
    @Mock ArtisanCertificationRepository certificationRepository;
    @Mock FormationFileRepository formationFileRepository;
    @Mock FormationRepository formationRepository;
    @Mock FormationEnrollmentRepository formationEnrollmentRepository;
    @Mock ArtisanRepository artisanRepository;
    @Mock FileUrlResolver fileUrlResolver;
    @Mock AccessControlService accessControlService;
    @Mock MessageAttachmentRepository messageAttachmentRepository;
    @Mock UserRepository userRepository;
    @Mock FeedPostMediaRepository feedPostMediaRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorize_allowsOwnedMessageAttachmentOnlyWithFileReadPermission() {
        User user = User.builder().email("client@example.com").build();
        MessageAttachment attachment = new MessageAttachment();
        String key = "message-attachment-key";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "credentials", List.of()));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(messageAttachmentRepository.findAccessibleByStorageKey(key, user)).thenReturn(Optional.of(attachment));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);

        assertThat(fileAccessService().authorize(key)).isFalse();
        verify(accessControlService).hasPermission(any(), eq(Permission.File.READ));
    }

    @Test
    void authorize_rejectsOwnedMessageAttachmentWithoutFileReadPermission() {
        User user = User.builder().email("client@example.com").build();
        String key = "protected-message-key";
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "credentials", List.of()));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(messageAttachmentRepository.findAccessibleByStorageKey(key, user)).thenReturn(Optional.of(new MessageAttachment()));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(false);

        assertThatThrownBy(() -> fileAccessService().authorize(key))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void authorize_allowsPublicAvatarGalleryAndFormationThumbnail() {
        when(userAvatarRepository.findFirstByStorageKeyOriginalOrStorageKeyMediumOrStorageKeyThumbnail("avatar", "avatar", "avatar"))
                .thenReturn(Optional.of(mock(UserAvatar.class)));
        assertThat(fileAccessService().authorize("avatar")).isTrue();

        when(userAvatarRepository.findFirstByStorageKeyOriginalOrStorageKeyMediumOrStorageKeyThumbnail("gallery", "gallery", "gallery"))
                .thenReturn(Optional.empty());
        when(fileUrlResolver.toUrl("gallery")).thenReturn("/files/gallery");
        when(galleryImageRepository.findByImageUrlAndDeletedAtIsNull("/files/gallery"))
                .thenReturn(Optional.of(mock(ArtisanGalleryImage.class)));
        assertThat(fileAccessService().authorize("gallery")).isTrue();

        when(userAvatarRepository.findFirstByStorageKeyOriginalOrStorageKeyMediumOrStorageKeyThumbnail("thumbnail", "thumbnail", "thumbnail"))
                .thenReturn(Optional.empty());
        when(fileUrlResolver.toUrl("thumbnail")).thenReturn("/files/thumbnail");
        when(formationRepository.findByThumbnailUrlAndDeletedAtIsNull("/files/thumbnail"))
                .thenReturn(Optional.of(mock(Formation.class)));
        assertThat(fileAccessService().authorize("thumbnail")).isTrue();
    }

    @Test
    void authorize_allowsCertificationOwnerAndAdministrator() {
        User owner = User.builder().email("owner@example.com").build();
        Artisan artisan = Artisan.builder().user(owner).build();
        ArtisanCertification certification = ArtisanCertification.builder().artisan(artisan).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.getEmail(), "credentials", List.of()));
        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(fileUrlResolver.toUrl("certificate")).thenReturn("/files/certificate");
        when(certificationRepository.findByDocumentUrlAndDeletedAtIsNull("/files/certificate"))
                .thenReturn(Optional.of(certification));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.USERS))).thenReturn(false);

        assertThat(fileAccessService().authorize("certificate")).isFalse();

        when(accessControlService.hasPermission(any(), eq(Permission.Admin.USERS))).thenReturn(true);
        assertThat(fileAccessService().authorize("certificate")).isFalse();
    }

    @Test
    void authorize_allowsVerifiedCertificationForAnyUserWithFileReadPermission() {
        User owner = User.builder().email("owner@example.com").build();
        User viewer = User.builder().email("viewer@example.com").build();
        Artisan artisan = Artisan.builder().user(owner).build();
        ArtisanCertification certification = ArtisanCertification.builder()
                .artisan(artisan)
                .isVerified(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewer.getEmail(), "credentials", List.of()));
        when(userRepository.findByEmail(viewer.getEmail())).thenReturn(Optional.of(viewer));
        when(fileUrlResolver.toUrl("verified-cert")).thenReturn("/files/verified-cert");
        when(certificationRepository.findByDocumentUrlAndDeletedAtIsNull("/files/verified-cert"))
                .thenReturn(Optional.of(certification));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.USERS))).thenReturn(false);

        assertThat(fileAccessService().authorize("verified-cert")).isFalse();
    }

    @Test
    void authorize_rejectsCertificationForDifferentUserAndMissingFormationFile() {
        User owner = User.builder().email("owner@example.com").build();
        User viewer = User.builder().email("viewer@example.com").build();
        Artisan artisan = Artisan.builder().user(owner).build();
        ArtisanCertification certification = ArtisanCertification.builder().artisan(artisan).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewer.getEmail(), "credentials", List.of()));
        when(userRepository.findByEmail(viewer.getEmail())).thenReturn(Optional.of(viewer));
        when(fileUrlResolver.toUrl("certificate")).thenReturn("/files/certificate");
        when(certificationRepository.findByDocumentUrlAndDeletedAtIsNull("/files/certificate"))
                .thenReturn(Optional.of(certification));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.USERS))).thenReturn(false);
        assertThatThrownBy(() -> fileAccessService().authorize("certificate"))
                .isInstanceOf(ForbiddenException.class);

        when(certificationRepository.findByDocumentUrlAndDeletedAtIsNull("/files/missing")).thenReturn(Optional.empty());
        when(fileUrlResolver.toUrl("missing")).thenReturn("/files/missing");
        assertThatThrownBy(() -> fileAccessService().authorize("missing"))
                .isInstanceOf(ResourceNotFoundException.class);

        when(certificationRepository.findByDocumentUrlAndDeletedAtIsNull("/files/stale"))
                .thenReturn(Optional.of(certification), Optional.empty());
        when(fileUrlResolver.toUrl("stale")).thenReturn("/files/stale");
        assertThatThrownBy(() -> fileAccessService().authorize("stale"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void authorize_allowsFormationFileForAuthorAndConfirmedParticipant() {
        User authorUser = User.builder().email("author@example.com").build();
        Artisan author = Artisan.builder().id("author-artisan").user(authorUser).build();
        Formation formation = Formation.builder().author(author).build();
        formation.setId("formation");
        FormationFile file = FormationFile.builder().formation(formation).storageKey("syllabus").build();

        authenticate(authorUser, Permission.Artisan.FORMATIONS);
        when(userRepository.findByEmail(authorUser.getEmail())).thenReturn(Optional.of(authorUser));
        when(formationFileRepository.findByStorageKeyAndDeletedAtIsNull("syllabus")).thenReturn(Optional.of(file));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.FORMATIONS))).thenReturn(false);
        when(artisanRepository.findByUserEmailIgnoreCase(authorUser.getEmail())).thenReturn(Optional.of(author));
        when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                "formation", "author-artisan", EnrollmentStatus.CONFIRMED)).thenReturn(false);

        assertThat(fileAccessService().authorize("syllabus")).isFalse();

        User participantUser = User.builder().email("participant@example.com").build();
        Artisan participant = Artisan.builder().id("participant-artisan").user(participantUser).build();
        authenticate(participantUser, Permission.Artisan.FORMATIONS);
        when(userRepository.findByEmail(participantUser.getEmail())).thenReturn(Optional.of(participantUser));
        when(artisanRepository.findByUserEmailIgnoreCase(participantUser.getEmail())).thenReturn(Optional.of(participant));
        when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                "formation", "participant-artisan", EnrollmentStatus.CONFIRMED)).thenReturn(true);

        assertThat(fileAccessService().authorize("syllabus")).isFalse();
    }

    @Test
    void authorize_allowsFormationFileForAttendedParticipant() {
        User authorUser = User.builder().email("author@example.com").build();
        Artisan author = Artisan.builder().id("author-artisan").user(authorUser).build();
        Formation formation = Formation.builder().author(author).build();
        formation.setId("formation");
        FormationFile file = FormationFile.builder().formation(formation).storageKey("syllabus").build();

        User participantUser = User.builder().email("attended@example.com").build();
        Artisan participant = Artisan.builder().id("attended-artisan").user(participantUser).build();
        authenticate(participantUser, Permission.Artisan.FORMATIONS);
        when(userRepository.findByEmail(participantUser.getEmail())).thenReturn(Optional.of(participantUser));
        when(formationFileRepository.findByStorageKeyAndDeletedAtIsNull("syllabus")).thenReturn(Optional.of(file));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.FORMATIONS))).thenReturn(false);
        when(artisanRepository.findByUserEmailIgnoreCase(participantUser.getEmail())).thenReturn(Optional.of(participant));
        when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                "formation", "attended-artisan", EnrollmentStatus.CONFIRMED)).thenReturn(false);
        when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                "formation", "attended-artisan", EnrollmentStatus.ATTENDED)).thenReturn(true);

        assertThat(fileAccessService().authorize("syllabus")).isFalse();
    }

    @Test
    void authorize_rejectsUnenrolledFormationFileAndAllowsAdministrator() {
        User user = User.builder().email("viewer@example.com").build();
        Artisan viewer = Artisan.builder().id("viewer-artisan").user(user).build();
        Artisan author = Artisan.builder().id("author-artisan").user(User.builder().email("author@example.com").build()).build();
        Formation formation = Formation.builder().author(author).build();
        formation.setId("formation");
        FormationFile file = FormationFile.builder().formation(formation).storageKey("protected").build();

        authenticate(user, Permission.Artisan.FORMATIONS);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(formationFileRepository.findByStorageKeyAndDeletedAtIsNull("protected")).thenReturn(Optional.of(file));
        when(accessControlService.hasPermission(any(), eq(Permission.File.READ))).thenReturn(true);
        when(accessControlService.hasPermission(any(), eq(Permission.Admin.FORMATIONS))).thenReturn(false);
        when(artisanRepository.findByUserEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(viewer));
        when(formationEnrollmentRepository.existsByFormationIdAndArtisanIdAndStatus(
                "formation", "viewer-artisan", EnrollmentStatus.CONFIRMED)).thenReturn(false);

        assertThatThrownBy(() -> fileAccessService().authorize("protected"))
                .isInstanceOf(ForbiddenException.class);

        when(accessControlService.hasPermission(any(), eq(Permission.Admin.FORMATIONS))).thenReturn(true);
        assertThat(fileAccessService().authorize("protected")).isFalse();
    }

    @Test
    void authorize_allowsPublishedFeedPostMediaPublicly() {
        FeedPost post = FeedPost.builder().status(FeedPostStatus.PUBLISHED).build();
        FeedPostMedia media = FeedPostMedia.builder().post(post).storageKey("feed-image-key").build();

        when(feedPostMediaRepository.findByStorageKeyAndDeletedAtIsNull("feed-image-key"))
                .thenReturn(Optional.of(media));

        assertThat(fileAccessService().authorize("feed-image-key")).isTrue();
    }

    @Test
    void authorize_allowsAuthorAndModeratorForPendingFeedPostMedia() {
        User author = User.builder().email("artisan@example.com").build();
        author.setId("author-id");
        FeedPost post = FeedPost.builder().author(author).status(FeedPostStatus.PENDING).build();
        FeedPostMedia media = FeedPostMedia.builder().post(post).storageKey("pending-media-key").build();

        when(feedPostMediaRepository.findByStorageKeyAndDeletedAtIsNull("pending-media-key"))
                .thenReturn(Optional.of(media));

        // As author
        authenticate(author, Permission.Artisan.CONTENT);
        when(userRepository.findByEmail(author.getEmail())).thenReturn(Optional.of(author));
        when(accessControlService.canModerateFeed(any())).thenReturn(false);

        assertThat(fileAccessService().authorize("pending-media-key")).isFalse();

        // As moderator
        User mod = User.builder().email("mod@example.com").build();
        mod.setId("mod-id");
        authenticate(mod, Permission.Admin.FEED);
        when(accessControlService.canModerateFeed(any())).thenReturn(true);

        assertThat(fileAccessService().authorize("pending-media-key")).isFalse();
    }

    @Test
    void authorize_rejectsOtherUsersForPendingFeedPostMedia() {
        User author = User.builder().email("artisan@example.com").build();
        author.setId("author-id");
        FeedPost post = FeedPost.builder().author(author).status(FeedPostStatus.PENDING).build();
        FeedPostMedia media = FeedPostMedia.builder().post(post).storageKey("pending-media-key").build();

        when(feedPostMediaRepository.findByStorageKeyAndDeletedAtIsNull("pending-media-key"))
                .thenReturn(Optional.of(media));

        User stranger = User.builder().email("stranger@example.com").build();
        stranger.setId("stranger-id");
        authenticate(stranger, Permission.Profile.READ);
        when(userRepository.findByEmail(stranger.getEmail())).thenReturn(Optional.of(stranger));
        when(accessControlService.canModerateFeed(any())).thenReturn(false);

        assertThatThrownBy(() -> fileAccessService().authorize("pending-media-key"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void authenticate(User user, Permission authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), "credentials", List.of(
                        authority)));
    }

    private FileAccessService fileAccessService() {
        return new FileAccessService(userAvatarRepository, galleryImageRepository, certificationRepository,
                formationFileRepository, formationRepository, formationEnrollmentRepository, artisanRepository,
                fileUrlResolver, accessControlService, messageAttachmentRepository, userRepository, feedPostMediaRepository);
    }
}
