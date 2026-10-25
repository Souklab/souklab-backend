package com.project.souklab.service.formateur;

import com.project.souklab.config.ArtisanProperties;
import com.project.souklab.dao.ArtisanFormateurRequestRepository;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.formateur.FormateurApproveDTO;
import com.project.souklab.dto.formateur.FormateurCooldownOverrideDTO;
import com.project.souklab.dto.formateur.FormateurGrantDTO;
import com.project.souklab.dto.formateur.FormateurRejectDTO;
import com.project.souklab.dto.formateur.FormateurRequestDTO;
import com.project.souklab.dto.formateur.FormateurRequestResponseDTO;
import com.project.souklab.dto.formateur.FormateurRevokeDTO;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanFormateurRequest;
import com.project.souklab.model.FormateurRequestStatus;
import com.project.souklab.model.NotificationType;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import com.project.souklab.service.notification.NotificationService;
import com.project.souklab.util.EmailUtil;
import com.project.souklab.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArtisanFormateurService {

    private static final String ERROR_ADMIN_NOT_FOUND_PREFIX = "Admin not found: ";
    private static final String ERROR_ARTISAN_NOT_FOUND_PREFIX = "Artisan not found with id: ";
    private static final String ERROR_REQUEST_NOT_FOUND_PREFIX = "Formateur request not found with id: ";
    private static final String ERROR_REQUEST_ALREADY_PREFIX = "Request is already ";

    private final ArtisanFormateurRequestRepository formateurRequestRepository;
    private final ArtisanRepository artisanRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final EmailUtil emailUtil;
    private final ArtisanProperties artisanProperties;
    private final Clock clock;

    /**
     * Submits a new formateur status request for the authenticated artisan.
     * Dual-dispatch to all admins: in-app + email.
     */
    @Transactional
    public FormateurRequestResponseDTO submitRequest(FormateurRequestDTO dto) {
        String email = SecurityUtils.getCurrentUsername();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new ForbiddenException("Your account must be ACTIVE to submit a Formateur request. Current status: " + user.getStatus());
        }

        Artisan profile = artisanRepository.findById(user.getId())
                .orElseThrow(() -> new ForbiddenException("Only registered artisans can request Formateur status."));

        if (profile.isTeacher()) {
            throw new ConflictException("You are already an approved Formateur.");
        }

        if (formateurRequestRepository.existsByArtisanAndStatusAndDeletedAtIsNull(profile, FormateurRequestStatus.PENDING)) {
            throw new ConflictException("FORMATEUR_REQUEST_PENDING", "You already have a pending Formateur request.");
        }

        Optional<ArtisanFormateurRequest> latestOpt = formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(profile);
        if (latestOpt.isPresent()) {
            ArtisanFormateurRequest latest = latestOpt.get();
            if (!latest.isCanReapply()) {
                throw new ForbiddenException("FORMATEUR_REQUEST_BLOCKED", "You are permanently blocked from submitting new Formateur requests.");
            }
            if (latest.getCooldownUntil() != null && latest.getCooldownUntil().isAfter(LocalDateTime.now(clock))) {
                throw new ForbiddenException("FORMATEUR_REQUEST_COOLDOWN", "You cannot submit a request during the cooldown period. Cooldown expires on: " + latest.getCooldownUntil());
            }
        }

        ArtisanFormateurRequest request = ArtisanFormateurRequest.builder()
                .artisan(profile)
                .status(FormateurRequestStatus.PENDING)
                .motivation(dto != null ? dto.getMotivation() : null)
                .canReapply(true)
                .cooldownUntil(null)
                .build();

        ArtisanFormateurRequest saved = formateurRequestRepository.saveAndFlush(request);

        List<User> admins = userRepository.findByPermissionKey(Permission.Admin.USERS.value());
        String artisanName = resolveArtisanFullName(user);
        String notifMsg = "New artisan formateur request submitted by "
                + (artisanName != null && !artisanName.isBlank() ? artisanName + " (" + user.getEmail() + ")" : user.getEmail())
                + (dto != null && dto.getMotivation() != null && !dto.getMotivation().isBlank()
                        ? ": \"" + dto.getMotivation() + "\"" : "");
        for (User admin : admins) {
            notificationService.createForUser(admin, notifMsg, NotificationType.Formateur.Request.SUBMITTED, saved.getId());
            emailUtil.sendFormateurRequestSubmittedNoticeToAdmin(admin.getEmail(), user.getEmail(), artisanName, dto != null ? dto.getMotivation() : null);
        }

        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public FormateurRequestResponseDTO getLatestRequest() {
        Artisan artisan = currentArtisan();
        return formateurRequestRepository.findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisan)
                .map(this::mapToDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Formateur request not found."));
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<FormateurRequestResponseDTO> getRequestHistory(Pageable pageable) {
        Artisan artisan = currentArtisan();
        return PaginatedResponse.from(formateurRequestRepository
                .findByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisan, pageable)
                .map(this::mapToDTO));
    }

    /**
     * Retrieves a paginated list of pending formateur requests for administrators.
     */
    @Transactional(readOnly = true)
    public PaginatedResponse<FormateurRequestResponseDTO> getPendingRequests(Pageable pageable) {
        Page<ArtisanFormateurRequest> page = formateurRequestRepository
                .findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(FormateurRequestStatus.PENDING, pageable);
        return PaginatedResponse.from(page.map(this::mapToDTO));
    }

    /**
     * Retrieves a single formateur request by ID for administrative review.
     *
     * @param requestId the unique identifier of the request
     * @return FormateurRequestResponseDTO
     * @throws ResourceNotFoundException if request is not found
     */
    @Transactional(readOnly = true)
    public FormateurRequestResponseDTO getRequestById(String requestId) {
        ArtisanFormateurRequest request = formateurRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_REQUEST_NOT_FOUND_PREFIX + requestId));
        return mapToDTO(request);
    }

    /**
     * Retrieves a single formateur request by ID belonging to the authenticated artisan.
     *
     * @param requestId the unique identifier of the request
     * @return FormateurRequestResponseDTO
     * @throws ResourceNotFoundException if request is not found or not owned by caller
     */
    @Transactional(readOnly = true)
    public FormateurRequestResponseDTO getMyRequestById(String requestId) {
        Artisan artisan = currentArtisan();
        ArtisanFormateurRequest request = formateurRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_REQUEST_NOT_FOUND_PREFIX + requestId));
        if (request.getArtisan() == null || !request.getArtisan().getId().equals(artisan.getId())) {
            throw new ResourceNotFoundException(ERROR_REQUEST_NOT_FOUND_PREFIX + requestId);
        }
        return mapToDTO(request);
    }

    /**
     * Approves a pending formateur request.
     */
    @Transactional
    public FormateurRequestResponseDTO approveRequest(String requestId, FormateurApproveDTO dto) {
        String adminEmail = SecurityUtils.getCurrentUsername();
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ADMIN_NOT_FOUND_PREFIX + adminEmail));

        ArtisanFormateurRequest request = formateurRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_REQUEST_NOT_FOUND_PREFIX + requestId));

        if (request.getStatus() != FormateurRequestStatus.PENDING) {
            throw new BadRequestException(ERROR_REQUEST_ALREADY_PREFIX + request.getStatus() + ".");
        }

        request.setStatus(FormateurRequestStatus.APPROVED);
        request.setAdminNote(dto.getAdminNote());
        request.setDecidedBy(admin);
        request.setDecidedAt(LocalDateTime.now(clock));

        Artisan artisan = request.getArtisan();
        artisan.setTeacher(true);
        artisanRepository.save(artisan);

        ArtisanFormateurRequest saved = formateurRequestRepository.saveAndFlush(request);

        User artisanUser = artisan.getUser();
        notificationService.createForUser(artisanUser, "Your request for Formateur status has been approved! Note: " + dto.getAdminNote(),
                NotificationType.Formateur.APPROVED, saved.getId());
        emailUtil.sendFormateurApprovedEmail(artisanUser.getEmail(), dto.getAdminNote());

        return mapToDTO(saved);
    }

    /**
     * Rejects a pending formateur request.
     */
    @Transactional
    public FormateurRequestResponseDTO rejectRequest(String requestId, FormateurRejectDTO dto) {
        String adminEmail = SecurityUtils.getCurrentUsername();
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ADMIN_NOT_FOUND_PREFIX + adminEmail));

        ArtisanFormateurRequest request = formateurRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_REQUEST_NOT_FOUND_PREFIX + requestId));

        if (request.getStatus() != FormateurRequestStatus.PENDING) {
            throw new BadRequestException(ERROR_REQUEST_ALREADY_PREFIX + request.getStatus() + ".");
        }

        boolean canReapply = dto.getCanReapply() == null || Boolean.TRUE.equals(dto.getCanReapply());
        LocalDateTime cooldownUntil = null;
        if (canReapply) {
            long cooldownDays = artisanProperties.getFormateur().getReapplyCooldownDays();
            cooldownUntil = dto.getCooldownUntil() != null ? dto.getCooldownUntil() : LocalDateTime.now(clock).plusDays(cooldownDays);
        }

        request.setStatus(FormateurRequestStatus.REJECTED);
        request.setAdminNote(dto.getAdminNote());
        request.setCanReapply(canReapply);
        request.setCooldownUntil(cooldownUntil);
        request.setDecidedBy(admin);
        request.setDecidedAt(LocalDateTime.now(clock));

        ArtisanFormateurRequest saved = formateurRequestRepository.saveAndFlush(request);

        User artisanUser = request.getArtisan().getUser();
        notificationService.createForUser(artisanUser, "Your Formateur request was rejected. Note: " + dto.getAdminNote(),
                NotificationType.Formateur.REJECTED, saved.getId());
        emailUtil.sendFormateurRejectedEmail(artisanUser.getEmail(), dto.getAdminNote(), cooldownUntil, canReapply);

        return mapToDTO(saved);
    }

    /**
     * Directly grants formateur status to an artisan without a prior request.
     */
    @Transactional
    public FormateurRequestResponseDTO grantDirectly(String artisanId, FormateurGrantDTO dto) {
        String adminEmail = SecurityUtils.getCurrentUsername();
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ADMIN_NOT_FOUND_PREFIX + adminEmail));

        Artisan artisan = artisanRepository.findById(artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        if (artisan.isTeacher()) {
            throw new BadRequestException("Artisan is already an approved Formateur.");
        }

        artisan.setTeacher(true);
        artisanRepository.save(artisan);

        ArtisanFormateurRequest auditRecord = ArtisanFormateurRequest.builder()
                .artisan(artisan)
                .status(FormateurRequestStatus.APPROVED)
                .motivation(null)
                .adminNote(dto.getAdminNote())
                .canReapply(true)
                .cooldownUntil(null)
                .decidedBy(admin)
                .decidedAt(LocalDateTime.now(clock))
                .build();

        ArtisanFormateurRequest saved = formateurRequestRepository.saveAndFlush(auditRecord);

        User artisanUser = artisan.getUser();
        notificationService.createForUser(artisanUser, "You have been granted Formateur status by an administrator! Note: " + dto.getAdminNote(),
                NotificationType.Formateur.GRANTED, saved.getId());
        emailUtil.sendFormateurGrantedEmail(artisanUser.getEmail(), dto.getAdminNote());

        return mapToDTO(saved);
    }

    /**
     * Directly revokes formateur status from an artisan.
     */
    @Transactional
    public void revokeDirectly(String artisanId, FormateurRevokeDTO dto) {
        String adminEmail = SecurityUtils.getCurrentUsername();
        userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ADMIN_NOT_FOUND_PREFIX + adminEmail));

        Artisan artisan = artisanRepository.findById(artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        if (!artisan.isTeacher()) {
            throw new BadRequestException("Artisan is not currently an approved Formateur.");
        }

        artisan.setTeacher(false);
        artisanRepository.save(artisan);

        User artisanUser = artisan.getUser();
        notificationService.createForUser(artisanUser, "Your Formateur status has been revoked. Reason: " + dto.getReason(),
                NotificationType.Formateur.REVOKED, artisanId);
        emailUtil.sendFormateurRevokedEmail(artisanUser.getEmail(), dto.getReason());
    }

    /**
     * Administratively overrides/lifts the cooldown or reapply block on an artisan's latest request record.
     */
    @Transactional
    public FormateurRequestResponseDTO liftCooldown(String artisanId, FormateurCooldownOverrideDTO dto) {
        Artisan artisan = artisanRepository.findById(artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        ArtisanFormateurRequest request = formateurRequestRepository
                .findFirstByArtisanAndDeletedAtIsNullOrderByCreatedAtDesc(artisan)
                .orElseThrow(() -> new ResourceNotFoundException("No Formateur request record found for artisan ID: " + artisanId));

        if (dto.getCanReapply() != null) {
            request.setCanReapply(dto.getCanReapply());
        }
        if (dto.getCooldownUntil() != null) {
            request.setCooldownUntil(dto.getCooldownUntil());
        } else if (Boolean.TRUE.equals(dto.getCanReapply())) {
            request.setCooldownUntil(null);
        }

        ArtisanFormateurRequest saved = formateurRequestRepository.saveAndFlush(request);
        return mapToDTO(saved);
    }

    private FormateurRequestResponseDTO mapToDTO(ArtisanFormateurRequest req) {
        User user = req.getArtisan() != null ? req.getArtisan().getUser() : null;
        String artisanName = resolveArtisanFullName(user);

        return FormateurRequestResponseDTO.builder()
                .id(req.getId())
                .artisanId(req.getArtisan() != null ? req.getArtisan().getId() : null)
                .artisanName(artisanName)
                .artisanEmail(user != null ? user.getEmail() : null)
                .status(req.getStatus())
                .motivation(req.getMotivation())
                .adminNote(req.getAdminNote())
                .canReapply(req.isCanReapply())
                .cooldownUntil(req.getCooldownUntil())
                .decidedByAdminId(req.getDecidedBy() != null ? req.getDecidedBy().getId() : null)
                .decidedByAdminEmail(req.getDecidedBy() != null ? req.getDecidedBy().getEmail() : null)
                .decidedAt(req.getDecidedAt())
                .createdAt(req.getCreatedAt())
                .build();
    }

    private Artisan currentArtisan() {
        String email = SecurityUtils.getCurrentUsername();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
        return artisanRepository.findById(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Artisan profile not found."));
    }

    private static String resolveArtisanFullName(User user) {
        if (user == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (user.getFirstName() != null) {
            sb.append(user.getFirstName()).append(" ");
        }
        if (user.getLastName() != null) {
            sb.append(user.getLastName());
        }
        return sb.toString().trim();
    }
}
