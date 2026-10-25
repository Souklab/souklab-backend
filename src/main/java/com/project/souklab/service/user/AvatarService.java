package com.project.souklab.service.user;

import com.project.souklab.analytics.AnalyticsMetric;
import com.project.souklab.util.LogSanitizer;
import org.springframework.beans.factory.annotation.Autowired;

import com.project.souklab.config.AvatarProperties;
import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.OperationalMetrics;
import com.project.souklab.dao.UserAvatarRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.user.AvatarResponseDTO;
import com.project.souklab.exception.AvatarLimitExceededException;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.filestorage.image.ImageProcessingService;
import com.project.souklab.filestorage.image.ImageVariant;
import com.project.souklab.filestorage.image.ResolutionTier;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.User;
import com.project.souklab.model.UserAvatar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrator service managing user avatar lifecycle and gallery storage.
 * Enforces per-user gallery quotas, executes input validation, virus scanning, multi-tier
 * image processing, storage persistence with rollback compensation, and transactional activation.
 */
@Service
@Slf4j
public class AvatarService {

    private static final String CURRENT_USER_CANNOT_BE_NULL = "Current user cannot be null";
    private static final String ERROR_AVATAR_NOT_FOUND_PREFIX = "Avatar not found with id: ";

    private final UserAvatarRepository userAvatarRepository;
    private final UserRepository userRepository;
    private final FileValidator fileValidator;
    private final VirusScanService virusScanService;
    private final ImageProcessingService imageProcessingService;
    private final StorageService storageService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final AvatarProperties avatarProperties;
    private final AppStorageProperties appStorageProperties;
    private final CurrentUserProvider currentUserProvider;
    private final OperationalMetrics metrics;

    @Autowired
    public AvatarService(UserAvatarRepository userAvatarRepository,
                         UserRepository userRepository,
                         FileValidator fileValidator,
                         VirusScanService virusScanService,
                         ImageProcessingService imageProcessingService,
                         StorageService storageService,
                         TransactionTemplate transactionTemplate,
                         Clock clock,
                         AvatarProperties avatarProperties,
                         AppStorageProperties appStorageProperties,
                         CurrentUserProvider currentUserProvider,
                         OperationalMetrics metrics) {
        this.userAvatarRepository = userAvatarRepository;
        this.userRepository = userRepository;
        this.fileValidator = fileValidator;
        this.virusScanService = virusScanService;
        this.imageProcessingService = imageProcessingService;
        this.storageService = storageService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.avatarProperties = avatarProperties;
        this.appStorageProperties = appStorageProperties;
        this.currentUserProvider = currentUserProvider;
        this.metrics = metrics;
    }

    /** Compatibility constructor retained for focused unit tests and existing callers. */
    public AvatarService(UserAvatarRepository userAvatarRepository,
                         UserRepository userRepository,
                         FileValidator fileValidator,
                         VirusScanService virusScanService,
                         ImageProcessingService imageProcessingService,
                         StorageService storageService,
                         TransactionTemplate transactionTemplate,
                         Clock clock,
                         AvatarProperties avatarProperties,
                         AppStorageProperties appStorageProperties,
                         CurrentUserProvider currentUserProvider) {
        this(userAvatarRepository, userRepository, fileValidator, virusScanService,
                imageProcessingService, storageService, transactionTemplate, clock,
                avatarProperties, appStorageProperties, currentUserProvider, OperationalMetrics.noop());
    }

    /** Compatibility constructor retained for focused unit tests and existing callers. */
    public AvatarService(UserAvatarRepository userAvatarRepository,
                         UserRepository userRepository,
                         FileValidator fileValidator,
                         VirusScanService virusScanService,
                         ImageProcessingService imageProcessingService,
                         StorageService storageService,
                         TransactionTemplate transactionTemplate,
                         Clock clock,
                         AvatarProperties avatarProperties) {
        this(userAvatarRepository, userRepository, fileValidator, virusScanService,
                imageProcessingService, storageService, transactionTemplate, clock,
                avatarProperties, new AppStorageProperties(), null, OperationalMetrics.noop());
    }

    /** Service entry point that owns principal and entity resolution. */
    @Transactional
    public AvatarResponseDTO uploadAvatar(MultipartFile file) {
        return uploadAvatar(currentUserProvider.requireCurrentUser(), file);
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<AvatarResponseDTO> listAvatars(Pageable pageable) {
        return listAvatars(currentUserProvider.requireCurrentUser(), pageable);
    }

    @Transactional(readOnly = true)
    public AvatarResponseDTO getAvatar(String avatarId) {
        return getAvatar(currentUserProvider.requireCurrentUser(), avatarId);
    }

    @Transactional(readOnly = true)
    public AvatarResponseDTO getAvatar(User currentUser, String avatarId) {
        if (currentUser == null) {
            throw new IllegalArgumentException(CURRENT_USER_CANNOT_BE_NULL);
        }
        UserAvatar avatar = userAvatarRepository.findByIdAndUserId(avatarId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_AVATAR_NOT_FOUND_PREFIX + avatarId));
        return mapToResponseDTO(avatar);
    }

    @Transactional
    public void deleteAvatar(String avatarId) {
        deleteAvatar(currentUserProvider.requireCurrentUser(), avatarId);
    }

    @Transactional
    public AvatarResponseDTO activateAvatar(String avatarId) {
        return activateAvatar(currentUserProvider.requireCurrentUser(), avatarId);
    }

    /**
     * Uploads, processes, stores, and activates a new avatar for the authenticated user.
     * Execution pipeline follows strict sequencing:
     * <ol>
     *   <li>Quota verification (must not exceed configured maximum avatars).</li>
     *   <li>File validation and sanitization against image-only MIME constraints.</li>
     *   <li>Antivirus scanning.</li>
     *   <li>Three-tier image variant generation (Original, Medium, Thumbnail).</li>
     *   <li>Storage persistence across all 3 tiers with compensating cleanup on failure.</li>
     *   <li>Transactional database persistence, active avatar switching, and User profile synchronization.</li>
     * </ol>
     *
     * @param currentUser the authenticated user uploading the avatar
     * @param file the multipart avatar image payload
     * @return AvatarResponseDTO containing image URLs for all three resolution tiers
     * @throws BadRequestException if the file is missing or empty
     * @throws AvatarLimitExceededException if the user has reached the configured avatar limit
     * @throws StorageException if variant generation or storage operations fail
     */
    @Transactional
    public AvatarResponseDTO uploadAvatar(User currentUser, MultipartFile file) {
        if (currentUser == null) {
            throw new IllegalArgumentException(CURRENT_USER_CANNOT_BE_NULL);
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Avatar file is required and cannot be empty");
        }

        User authenticatedUser = userRepository.findWithLockById(currentUser.getId())
                .orElseGet(() -> userRepository.findById(currentUser.getId()).orElse(currentUser));
        long existingCount = userAvatarRepository.countByUserId(authenticatedUser.getId());
        if (existingCount >= avatarProperties.getMaxPerUser()) {
            log.warn("Avatar upload rejected for user {}: quota limit of {} avatars reached",
                    authenticatedUser.getId(), avatarProperties.getMaxPerUser());
            throw new AvatarLimitExceededException(
                    "Maximum avatar limit of " + avatarProperties.getMaxPerUser() + " reached. Please delete an existing avatar before uploading a new one."
            );
        }

        ValidatedFile validatedFile;
        try {
            validatedFile = fileValidator.validateAndSanitize(
                    file.getInputStream(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    avatarProperties.getAllowedMimeTypes()
            );
        } catch (IOException e) {
            log.error("Failed to read avatar upload stream for user {}", authenticatedUser.getId(), e);
            throw new StorageException("Failed to read uploaded avatar stream: " + e.getMessage(), e);
        }

        ValidatedFile scannedFile = virusScanService.scan(validatedFile);

        Map<ResolutionTier, ImageVariant> variants = imageProcessingService.generateVariants(scannedFile);
        ImageVariant originalVariant = variants.get(ResolutionTier.ORIGINAL);
        ImageVariant mediumVariant = variants.get(ResolutionTier.MEDIUM);
        ImageVariant thumbnailVariant = variants.get(ResolutionTier.THUMBNAIL);

        if (originalVariant == null || mediumVariant == null || thumbnailVariant == null) {
            throw new StorageException("Image processing failed to generate all required resolution tiers");
        }

        List<String> storedKeys = new ArrayList<>(3);
        String originalKey;
        String mediumKey;
        String thumbnailKey;

        try {
            StorageResult origResult = storageService.store(
                    originalVariant.getInputStream(),
                    scannedFile.sanitizedFilename(),
                    originalVariant.contentType(),
                    originalVariant.getSize()
            );
            originalKey = origResult.key();
            storedKeys.add(originalKey);

            StorageResult medResult = storageService.store(
                    mediumVariant.getInputStream(),
                    scannedFile.sanitizedFilename(),
                    mediumVariant.contentType(),
                    mediumVariant.getSize()
            );
            mediumKey = medResult.key();
            storedKeys.add(mediumKey);

            StorageResult thumbResult = storageService.store(
                    thumbnailVariant.getInputStream(),
                    scannedFile.sanitizedFilename(),
                    thumbnailVariant.contentType(),
                    thumbnailVariant.getSize()
            );
            thumbnailKey = thumbResult.key();
            storedKeys.add(thumbnailKey);

            AvatarResponseDTO response = transactionTemplate.execute(status -> {
                userAvatarRepository.findByUserIdAndIsActiveTrue(authenticatedUser.getId())
                        .ifPresent(previousActive -> {
                            previousActive.setActive(false);
                            userAvatarRepository.save(previousActive);
                        });

                UserAvatar newAvatar = UserAvatar.builder()
                        .user(authenticatedUser)
                        .storageKeyOriginal(originalKey)
                        .storageKeyMedium(mediumKey)
                        .storageKeyThumbnail(thumbnailKey)
                        .originalFilename(scannedFile.sanitizedFilename())
                        .contentType(scannedFile.detectedMimeType())
                        .fileSize(scannedFile.size())
                        .isActive(true)
                        .uploadedAt(LocalDateTime.now(clock))
                        .build();

                UserAvatar savedAvatar = userAvatarRepository.save(newAvatar);

                syncUserAvatar(authenticatedUser, thumbnailKey);

                return mapToResponseDTO(savedAvatar);
            });
            metrics.recordUpload(AnalyticsMetric.Operational.Operation.AVATAR,
                    AnalyticsMetric.Operational.Outcome.SUCCESS);
            return response;
        } catch (Exception ex) {
            metrics.recordUpload(AnalyticsMetric.Operational.Operation.AVATAR,
                    AnalyticsMetric.Operational.Outcome.FAILURE);
            log.error("Avatar upload pipeline failed for user {}. Initiating rollback compensation for {} stored keys: {}",
                    authenticatedUser.getId(), storedKeys.size(), storedKeys, ex);
            compensateStorageDeletions(storedKeys);
            throw ex;
        }
    }

    /**
     * Retrieves a paginated gallery list of avatars uploaded by the authenticated user.
     *
     * @param currentUser the authenticated user requesting their gallery
     * @param pageable pagination and sorting parameters
     * @return paginated response containing mapped AvatarResponseDTO objects
     */
    public PaginatedResponse<AvatarResponseDTO> listAvatars(User currentUser, Pageable pageable) {
        if (currentUser == null) {
            throw new IllegalArgumentException(CURRENT_USER_CANNOT_BE_NULL);
        }
        Page<UserAvatar> page = userAvatarRepository.findByUserId(currentUser.getId(), pageable);
        return PaginatedResponse.from(page.map(this::mapToResponseDTO));
    }

    /**
     * Hard-deletes an avatar belonging to the authenticated user from the database and storage.
     * If the avatar is currently active, the user's profile avatar URL is cleared to null without auto-promoting
     * another avatar. Physical storage variant deletion is executed best-effort after the database transaction commits.
     *
     * @param currentUser the authenticated user owning the avatar
     * @param avatarId the unique identifier of the avatar to delete
     * @throws ResourceNotFoundException if the avatar does not exist or does not belong to the user
     */
    public void deleteAvatar(User currentUser, String avatarId) {
        if (currentUser == null) {
            throw new IllegalArgumentException(CURRENT_USER_CANNOT_BE_NULL);
        }
        UserAvatar avatar = userAvatarRepository.findByIdAndUserId(avatarId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_AVATAR_NOT_FOUND_PREFIX + avatarId));

        List<String> keysToDelete = List.of(
                avatar.getStorageKeyOriginal(),
                avatar.getStorageKeyMedium(),
                avatar.getStorageKeyThumbnail()
        );

        boolean wasActive = avatar.isActive();

        transactionTemplate.execute(status -> {
            userAvatarRepository.delete(avatar);
            if (wasActive) {
                currentUser.setAvatarUrl(null);
                userRepository.save(currentUser);
            }
            return null;
        });

        for (String key : keysToDelete) {
            try {
                storageService.delete(key);
                log.debug("Deleted storage key '{}' for avatar '{}'", LogSanitizer.sanitize(key), LogSanitizer.sanitize(avatarId));
            } catch (Exception e) {
                log.error("Failed to delete storage key '{}' for avatar '{}'", LogSanitizer.sanitize(key), LogSanitizer.sanitize(avatarId), e);
            }
        }
    }

    /**
     * Activates a previous avatar for the authenticated user without re-uploading.
     * If the avatar is already active, this operation is an idempotent no-op.
     * Otherwise, any previously active avatar is set to inactive, the target avatar is activated,
     * and the user's profile avatar URL is updated to the target's thumbnail.
     *
     * @param currentUser the authenticated user activating the avatar
     * @param avatarId the unique identifier of the avatar to activate
     * @return AvatarResponseDTO of the activated avatar
     * @throws ResourceNotFoundException if the avatar does not exist or does not belong to the user
     */
    public AvatarResponseDTO activateAvatar(User currentUser, String avatarId) {
        if (currentUser == null) {
            throw new IllegalArgumentException(CURRENT_USER_CANNOT_BE_NULL);
        }
        UserAvatar avatar = userAvatarRepository.findByIdAndUserId(avatarId, currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_AVATAR_NOT_FOUND_PREFIX + avatarId));

        if (avatar.isActive()) {
            return mapToResponseDTO(avatar);
        }

        return transactionTemplate.execute(status -> {
            userAvatarRepository.findByUserIdAndIsActiveTrue(currentUser.getId())
                    .ifPresent(previousActive -> {
                        previousActive.setActive(false);
                        userAvatarRepository.save(previousActive);
                    });

            avatar.setActive(true);
            UserAvatar savedAvatar = userAvatarRepository.save(avatar);

            syncUserAvatar(currentUser, avatar.getStorageKeyThumbnail());

            return mapToResponseDTO(savedAvatar);
        });
    }

    /**
     * Executes compensating delete operations on storage keys that were persisted before a pipeline failure.
     *
     * @param keys list of storage keys to physically delete
     */
    private void compensateStorageDeletions(List<String> keys) {
        for (String key : keys) {
            try {
                storageService.delete(key);
                log.info("Rollback compensation: successfully deleted orphaned storage key '{}'", key);
            } catch (Exception e) {
                log.error("Rollback compensation failed to delete storage key '{}'", key, e);
            }
        }
    }

    /**
     * Synchronizes the user's active profile avatar URL with the specified thumbnail storage key.
     *
     * @param user the user whose profile avatar URL is being synchronized
     * @param thumbnailKey the storage key of the active thumbnail image variant
     */
    private void syncUserAvatar(User user, String thumbnailKey) {
        user.setAvatarUrl(appStorageProperties.toUrl(thumbnailKey));
        userRepository.save(user);
    }

    /**
     * Maps a persisted UserAvatar entity into an AvatarResponseDTO.
     *
     * @param avatar the entity to convert
     * @return the populated response DTO
     */
    private AvatarResponseDTO mapToResponseDTO(UserAvatar avatar) {
        return AvatarResponseDTO.builder()
                .id(avatar.getId())
                .urlOriginal(appStorageProperties.toUrl(avatar.getStorageKeyOriginal()))
                .urlMedium(appStorageProperties.toUrl(avatar.getStorageKeyMedium()))
                .urlThumbnail(appStorageProperties.toUrl(avatar.getStorageKeyThumbnail()))
                .originalFilename(avatar.getOriginalFilename())
                .contentType(avatar.getContentType())
                .fileSize(avatar.getFileSize())
                .isActive(avatar.isActive())
                .uploadedAt(avatar.getUploadedAt())
                .build();
    }
}
