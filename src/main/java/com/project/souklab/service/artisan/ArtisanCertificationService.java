package com.project.souklab.service.artisan;

import com.project.souklab.config.AppStorageProperties;
import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanCertificationRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.dto.artisan.CertificationUpdateDTO;
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
import com.project.souklab.model.ArtisanCertification;
import com.project.souklab.util.ArtisanSecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service managing official artisan credentials, diplomas, and CAM certifications.
 * Handles document validation, antivirus scanning, file storage with rollback compensation,
 * certification quotas, and soft-delete lifecycle operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArtisanCertificationService {

    private final ArtisanRepository artisanRepository;
    private final ArtisanCertificationRepository certificationRepository;
    private final StorageService storageService;
    private final StorageObjectLifecycle storageObjectLifecycle;
    private final FileUrlResolver fileUrlResolver;
    private final FileValidator fileValidator;
    private final VirusScanService virusScanService;
    private final ArtisanProperties artisanProperties;
    private final AppStorageProperties appStorageProperties;
    private final Clock clock;


    /**
     * Uploads and persists an official professional certification or accreditation card for the authenticated artisan.
     * Enforces the configured certification quota, validates MIME type and file size, executes antivirus scanning,
     * persists document in storage, and records entity with isVerified=false and storage rollback on failure.
     *
     * @param file the document or scan payload
     * @param title accreditation title (e.g. Carte d'Artisan Professionnel)
     * @param issuer issuing authority (e.g. CAM Alger)
     * @param issuedAt date of official issuance
     * @param expiresAt date of expiration if applicable
     * @return response DTO representing the persisted certification
     * @throws BadRequestException if quota is exceeded, file is missing, or required fields are blank
     * @throws FileTooLargeException if file size exceeds the configured limit
     * @throws ForbiddenException if authenticated user is not a registered artisan
     * @throws UnauthorizedException if no authenticated user is present
     */
    @Transactional
    public CertificationResponseDTO uploadCertification(
            MultipartFile file,
            String title,
            String issuer,
            LocalDate issuedAt,
            LocalDate expiresAt
    ) {
        Artisan artisan = resolveAuthenticatedArtisan();
        artisan = artisanRepository.findWithLockById(artisan.getId()).orElse(artisan);

        int maxCount = artisanProperties.getCertification().getMaxCount();
        long activeCount = certificationRepository.countByArtisanIdAndDeletedAtIsNull(artisan.getId());
        if (activeCount >= maxCount) {
            throw new BadRequestException("Certification quota exceeded. Maximum " + maxCount + " records allowed.");
        }

        validateCertificationParameters(file, title, issuer, issuedAt, expiresAt);

        ValidatedFile validatedFile = validateAndSanitizeFile(file);
        ValidatedFile scannedFile = virusScanService.scan(validatedFile);

        return storeAndPersistCertification(artisan, scannedFile, title, issuer, issuedAt, expiresAt);
    }

    /**
     * Retrieves all active professional certifications for the authenticated artisan sorted newest to oldest.
     *
     * @return list of active certifications
     */
    @Transactional(readOnly = true)
    public List<CertificationResponseDTO> getMyCertifications() {
        Artisan artisan = resolveAuthenticatedArtisan();
        return certificationRepository
                .findByArtisanIdAndDeletedAtIsNullOrderByCreatedAtDesc(artisan.getId())
                .stream()
                .map(CertificationResponseDTO::from)
                .toList();
    }

    /**
     * Retrieves a single professional certification belonging to the authenticated artisan.
     *
     * @param certificationId unique identifier of the certification
     * @return response DTO representing the certification
     * @throws ResourceNotFoundException if the certification is missing, deleted, or owned by another artisan
     */
    @Transactional(readOnly = true)
    public CertificationResponseDTO getCertification(String certificationId) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanCertification cert = certificationRepository
                .findByIdAndArtisanIdAndDeletedAtIsNull(certificationId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Certification not found."));
        return CertificationResponseDTO.from(cert);
    }

    @Transactional
    public CertificationResponseDTO updateCertification(String certificationId, CertificationUpdateDTO update, MultipartFile file) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanCertification cert = certificationRepository.findByIdAndArtisanIdAndDeletedAtIsNull(certificationId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Certification not found."));
        String oldUrl = cert.getDocumentUrl();
        String newStorageKey = null;
        try {
            if (update != null) {
                if (update.getTitle() != null) cert.setTitle(update.getTitle());
                if (update.getIssuer() != null) cert.setIssuer(update.getIssuer());
                if (update.getIssuedAt() != null) cert.setIssuedAt(update.getIssuedAt());
                if (update.getExpiresAt() != null) cert.setExpiresAt(update.getExpiresAt());
            }
            if (cert.getTitle() == null || cert.getTitle().isBlank() || cert.getIssuer() == null || cert.getIssuer().isBlank()) {
                throw new BadRequestException("Certification title and issuer are required.");
            }
            if (cert.getIssuedAt() != null && cert.getExpiresAt() != null && cert.getExpiresAt().isBefore(cert.getIssuedAt())) {
                throw new BadRequestException("Expiration date cannot be earlier than issuance date.");
            }
            if (file != null && !file.isEmpty()) {
                long max = artisanProperties.getCertification().getMaxFileSize().toBytes();
                if (file.getSize() > max) throw new FileTooLargeException(file.getSize(), max);
                ValidatedFile validated = virusScanService.scan(validateAndSanitizeFile(file));
                StorageResult stored = storageService.store(validated.content(), validated.sanitizedFilename(), validated.detectedMimeType(), validated.size());
                newStorageKey = stored.key();
                cert.setDocumentUrl(appStorageProperties.toUrl(newStorageKey));
            }
            cert.setVerified(false);
            CertificationResponseDTO response = CertificationResponseDTO.from(certificationRepository.save(cert));
            if (newStorageKey != null && oldUrl != null) {
                storageObjectLifecycle.deleteAfterCommit(fileUrlResolver.toStorageKey(oldUrl));
            }
            return response;
        } catch (Exception ex) {
            if (newStorageKey != null) {
                try { storageService.delete(newStorageKey); } catch (Exception cleanup) { log.error("Compensating certification replacement cleanup failed", cleanup); }
            }
            throw ex;
        }
    }

    /**
     * Soft-deletes a professional certification belonging to the authenticated artisan.
     *
     * @param certificationId unique identifier of the certification
     * @throws ResourceNotFoundException if the certification is missing, soft-deleted, or owned by another artisan
     */
    @Transactional
    public void deleteCertification(String certificationId) {
        Artisan artisan = resolveAuthenticatedArtisan();
        ArtisanCertification cert = certificationRepository
                .findByIdAndArtisanIdAndDeletedAtIsNull(certificationId, artisan.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Certification not found."));

        cert.setDeletedAt(LocalDateTime.now(clock));
        certificationRepository.save(cert);
        storageObjectLifecycle.deleteAfterCommit(fileUrlResolver.toStorageKey(cert.getDocumentUrl()));
    }

    /**
     * Validates input parameters for a certification upload.
     *
     * @param file multipart upload payload
     * @param title credential title
     * @param issuer issuing authority
     * @param issuedAt date of issuance
     * @param expiresAt date of expiration
     */
    private void validateCertificationParameters(
            MultipartFile file,
            String title,
            String issuer,
            LocalDate issuedAt,
            LocalDate expiresAt
    ) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Certification document file is required and cannot be empty.");
        }
        if (title == null || title.isBlank()) {
            throw new BadRequestException("Certification title is required.");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new BadRequestException("Certification issuer is required.");
        }
        if (issuedAt != null && expiresAt != null && expiresAt.isBefore(issuedAt)) {
            throw new BadRequestException("Expiration date cannot be earlier than issuance date.");
        }
        long maxFileSizeBytes = artisanProperties.getCertification().getMaxFileSize().toBytes();
        if (file.getSize() > maxFileSizeBytes) {
            throw new FileTooLargeException(file.getSize(), maxFileSizeBytes);
        }
    }

    /**
     * Executes file validation and MIME sanitization against allowed certification formats.
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
                    artisanProperties.getCertification().getAllowedMimeTypes()
            );
        } catch (IOException e) {
            log.error("Failed to read certification upload stream", e);
            throw new StorageException("Failed to read uploaded certification stream: " + e.getMessage(), e);
        }
    }

    /**
     * Stores document in storage provider and persists entity, rolling back storage file on failure.
     *
     * @param artisan owning artisan
     * @param scannedFile validated and scanned file descriptor
     * @param title credential title
     * @param issuer issuing authority
     * @param issuedAt issuance date
     * @param expiresAt expiration date
     * @return response DTO
     */
    private CertificationResponseDTO storeAndPersistCertification(
            Artisan artisan,
            ValidatedFile scannedFile,
            String title,
            String issuer,
            LocalDate issuedAt,
            LocalDate expiresAt
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

            ArtisanCertification certification = ArtisanCertification.builder()
                    .artisan(artisan)
                    .title(title)
                    .issuer(issuer)
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .documentUrl(appStorageProperties.toUrl(storageKey))
                    .isVerified(false)
                    .build();

            ArtisanCertification savedEntity = certificationRepository.save(certification);
            return CertificationResponseDTO.from(savedEntity);
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
     * Delegates artisan identity resolution to the shared {@link ArtisanSecurityUtils} component.
     *
     * @return resolved Artisan entity for the current authenticated principal
     */
    private Artisan resolveAuthenticatedArtisan() {
        return ArtisanSecurityUtils.resolveAuthenticatedArtisan(artisanRepository);
    }
}
