package com.project.souklab.service.artisan;

import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanGalleryImageRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.dto.artisan.GalleryImageUpdateDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.filestorage.StorageResult;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.filestorage.FileUrlResolver;
import com.project.souklab.filestorage.exception.FileTooLargeException;
import com.project.souklab.filestorage.exception.StorageException;
import com.project.souklab.filestorage.lifecycle.StorageObjectLifecycle;
import com.project.souklab.filestorage.scan.VirusScanService;
import com.project.souklab.filestorage.validation.FileValidator;
import com.project.souklab.filestorage.validation.ValidatedFile;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.util.ArtisanSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Service managing portfolio showcase gallery images for artisans.
 * Handles multipart file validation, antivirus scanning, file storage with rollback compensation,
 * gallery quotas, sequential reordering, and soft-delete lifecycle operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArtisanGalleryService {

    private final ArtisanRepository artisanRepository;
    private final ArtisanGalleryImageRepository galleryImageRepository;
    private final StorageService storageService;
    private final StorageObjectLifecycle storageObjectLifecycle;
    private final FileUrlResolver fileUrlResolver;
    private final FileValidator fileValidator;
    private final VirusScanService virusScanService;
    private final ArtisanProperties artisanProperties;
    private final AppStorageProperties appStorageProperties;
    private final Clock clock;


    /**
     * Uploads and persists a new portfolio showcase image for the authenticated artisan.
     * Enforces the configured image gallery quota, validates MIME type and file size, executes antivirus scanning,
     * persists the file in storage, and records the entity with rollback compensation on failure.
     *
     * @param file the multipart file payload
     * @param title optional title for the showcase image
     * @param caption optional narrative description or cultural background
     * @return response DTO representing the persisted gallery image
     * @throws BadRequestException if the quota is exceeded or the file is missing/empty
     * @throws FileTooLargeException if file size exceeds the configured limit
     * @throws ForbiddenException if authenticated user is not a registered artisan
     * @throws UnauthorizedException if no authenticated user is present
     */
    @Transactional
    public GalleryImageResponseDTO uploadImage(MultipartFile file, String title, String caption) {
        Artisan artisan = resolveAuthenticatedArtisan();
        artisan = artisanRepository.findWithLockById(artisan.getId()).orElse(artisan);

        int maxImages = artisanProperties.getGallery().getMaxImages();
        long activeCount = galleryImageRepository.countByArtisanIdAndDeletedAtIsNull(artisan.getId());
        if (activeCount >= maxImages) {
            throw new BadRequestException("Gallery quota exceeded. Maximum " + maxImages + " images allowed.");
        }

        validateUploadPayload(file);

        ValidatedFile validatedFile = validateAndSanitizeFile(file);
        ValidatedFile scannedFile = virusScanService.scan(validatedFile);

        int nextDisplayOrder = deriveNextDisplayOrder(artisan.getId());

        return storeAndPersistImage(artisan, scannedFile, title, caption, nextDisplayOrder);
    }

    /**
     * Retrieves all active gallery showcase images for the authenticated artisan ordered by display sequence.
     *
     * @return list of active gallery images in ascending display order
     */
    @Transactional(readOnly = true)
    public List<GalleryImageResponseDTO> getMyGallery() {
        Artisan artisan = resolveAuthenticatedArtisan();
        return galleryImageRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(artisan.getId())
                .stream()
                .map(GalleryImageResponseDTO::from)
                .toList();
    }

    /**
     * Retrieves a single showcase gallery image belonging to the authenticated artisan.
     *
     * @param imageId unique identifier of the gallery image
     * @return response DTO representing the gallery image
     * @throws ResourceNotFoundException if the image is missing, deleted, or owned by another artisan
     */
    @Transactional(readOnly = true)
    public GalleryImageResponseDTO getImage(String imageId) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanGalleryImage image = galleryImageRepository
                .findByIdAndArtisanIdAndDeletedAtIsNull(imageId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Gallery image not found."));
        return GalleryImageResponseDTO.from(image);
    }

    @Transactional
    public GalleryImageResponseDTO updateImage(String imageId, GalleryImageUpdateDTO update, MultipartFile file) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanGalleryImage image = galleryImageRepository.findByIdAndArtisanIdAndDeletedAtIsNull(imageId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Gallery image not found."));
        String oldUrl = image.getImageUrl();
        String newStorageKey = null;
        try {
            if (update != null) {
                image.setTitle(update.getTitle());
                image.setCaption(update.getCaption());
            }
            if (file != null && !file.isEmpty()) {
                validateUploadPayload(file);
                ValidatedFile validated = virusScanService.scan(validateAndSanitizeFile(file));
                StorageResult stored = storageService.store(validated.content(), validated.sanitizedFilename(), validated.detectedMimeType(), validated.size());
                newStorageKey = stored.key();
                image.setImageUrl(appStorageProperties.toUrl(newStorageKey));
            }
            GalleryImageResponseDTO response = GalleryImageResponseDTO.from(galleryImageRepository.save(image));
            if (newStorageKey != null) {
                storageObjectLifecycle.deleteAfterCommit(fileUrlResolver.toStorageKey(oldUrl));
            }
            return response;
        } catch (Exception ex) {
            if (newStorageKey != null) {
                try { storageService.delete(newStorageKey); } catch (Exception cleanup) { log.error("Compensating gallery replacement cleanup failed", cleanup); }
            }
            throw ex;
        }
    }

    /**
     * Updates display order sequence across all active gallery images for the authenticated artisan.
     *
     * @param imageIdsInOrder full list of image IDs in desired display sequence
     * @throws BadRequestException if input IDs do not match the artisan's active images exactly
     */
    @Transactional
    public void reorderGallery(List<String> imageIdsInOrder) {
        Artisan artisan = resolveAuthenticatedArtisan();
        if (imageIdsInOrder == null) {
            throw new BadRequestException("Image IDs list cannot be null.");
        }

        List<ArtisanGalleryImage> activeImages = galleryImageRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(artisan.getId());

        validateReorderSequence(activeImages, imageIdsInOrder);

        Map<String, ArtisanGalleryImage> imageMap = activeImages.stream()
                .collect(Collectors.toMap(ArtisanGalleryImage::getId, img -> img));

        for (int i = 0; i < imageIdsInOrder.size(); i++) {
            ArtisanGalleryImage img = imageMap.get(imageIdsInOrder.get(i));
            img.setDisplayOrder(i);
        }

        galleryImageRepository.saveAll(activeImages);
    }

    /**
     * Soft-deletes a gallery image belonging to the authenticated artisan.
     *
     * @param imageId unique identifier of the gallery image
     * @throws ResourceNotFoundException if the image is missing, soft-deleted, or owned by another artisan
     */
    @Transactional
    public void deleteImage(String imageId) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanGalleryImage image = galleryImageRepository
                .findByIdAndArtisanIdAndDeletedAtIsNull(imageId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Gallery image not found."));

        image.setDeletedAt(LocalDateTime.now(clock));
        galleryImageRepository.save(image);
        storageObjectLifecycle.deleteAfterCommit(fileUrlResolver.toStorageKey(image.getImageUrl()));
    }

    /**
     * Validates upload file existence and size bounds.
     *
     * @param file multipart upload payload
     */
    private void validateUploadPayload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Image file is required and cannot be empty.");
        }
        long maxFileSizeBytes = artisanProperties.getGallery().getMaxFileSize().toBytes();
        if (file.getSize() > maxFileSizeBytes) {
            throw new FileTooLargeException(file.getSize(), maxFileSizeBytes);
        }
    }

    /**
     * Executes file validation and MIME sanitization against allowed image formats.
     *
     * @param file multipart upload payload
     * @return validated file descriptor
     */
    private ValidatedFile validateAndSanitizeFile(MultipartFile file) {
        try {
            return fileValidator.validateAndSanitize(
                    file.getInputStream(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    artisanProperties.getGallery().getAllowedMimeTypes()
            );
        } catch (IOException e) {
            log.error("Failed to read gallery image upload stream", e);
            throw new StorageException("Failed to read uploaded image stream: " + e.getMessage(), e);
        }
    }

    /**
     * Stores file in storage provider and persists entity, rolling back storage file on failure.
     *
     * @param artisan owning artisan
     * @param scannedFile validated and scanned file descriptor
     * @param title optional image title
     * @param caption optional narrative description
     * @param displayOrder computed display order sequence
     * @return response DTO
     */
    private GalleryImageResponseDTO storeAndPersistImage(
            Artisan artisan,
            ValidatedFile scannedFile,
            String title,
            String caption,
            int displayOrder
    ) {
        String storageKey = null;
        try {
            StorageResult storageResult = storageService.store(
                    scannedFile.content(),
                    scannedFile.sanitizedFilename(),
                    scannedFile.detectedMimeType(),
                    scannedFile.size()
            );
            storageKey = storageResult.key();

            ArtisanGalleryImage galleryImage = ArtisanGalleryImage.builder()
                    .artisan(artisan)
                    .imageUrl(appStorageProperties.toUrl(storageKey))
                    .title(title)
                    .caption(caption)
                    .displayOrder(displayOrder)
                    .build();

            ArtisanGalleryImage savedEntity = galleryImageRepository.save(galleryImage);
            return GalleryImageResponseDTO.from(savedEntity);
        } catch (Exception ex) {
            if (storageKey != null) {
                try {
                    storageService.delete(storageKey);
                } catch (Exception deleteEx) {
                    log.error("Compensating delete failed for storage key '{}'", storageKey, deleteEx);
                }
            }
            throw ex;
        }
    }

    /**
     * Computes next display order based on current active images.
     *
     * @param artisanId artisan identifier
     * @return next sequential display order
     */
    private int deriveNextDisplayOrder(String artisanId) {
        List<ArtisanGalleryImage> activeImages = galleryImageRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(artisanId);
        return activeImages.stream()
                .mapToInt(ArtisanGalleryImage::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
    }

    /**
     * Validates that requested reorder sequence matches current active images set exactly.
     *
     * @param activeImages current active gallery images
     * @param imageIdsInOrder proposed ordering list
     */
    private void validateReorderSequence(List<ArtisanGalleryImage> activeImages, List<String> imageIdsInOrder) {
        if (activeImages.size() != imageIdsInOrder.size()) {
            throw new BadRequestException("Reorder list count does not match current active gallery image count.");
        }

        Set<String> uniqueInputIds = new HashSet<>(imageIdsInOrder);
        if (uniqueInputIds.size() != imageIdsInOrder.size()) {
            throw new BadRequestException("Reorder list contains duplicate image IDs.");
        }

        Set<String> existingIds = activeImages.stream()
                .map(ArtisanGalleryImage::getId)
                .collect(Collectors.toSet());

        if (!existingIds.equals(uniqueInputIds)) {
            throw new BadRequestException("Reorder list does not match artisan's active gallery image IDs.");
        }
    }

    /**
     * Delegates artisan identity resolution to the shared {@link ArtisanSecurityUtils} component.
     *
     * @return resolved Artisan entity for the current authenticated principal
     */
    private Artisan resolveAuthenticatedArtisan() {
        return ArtisanSecurityUtils.resolveAuthenticatedArtisan(artisanRepository);
    }
}
