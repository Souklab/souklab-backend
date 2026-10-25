package com.project.souklab.service.favorite;

import com.project.souklab.util.LogSanitizer;
import com.project.souklab.config.FavoritesProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ClientFavoriteArtisanRepository;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.directory.ArtisanDirectoryCardDTO;
import com.project.souklab.dto.favorite.ClientFavoriteArtisanItemDTO;
import com.project.souklab.dto.favorite.ClientFavoriteArtisanResponseDTO;
import com.project.souklab.dto.favorite.FavoriteStatusResponseDTO;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ForbiddenException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientFavoriteArtisan;
import com.project.souklab.model.User;
import com.project.souklab.security.ViewerPremiumResolver;
import com.project.souklab.service.user.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Production implementation of {@link ArtisanFavoriteService}.
 * Handles addition, pagination, status queries, and removal of artisan favorites for clients.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArtisanFavoriteServiceImpl implements ArtisanFavoriteService {

    private static final String ERROR_CLIENT_PROFILE_REQUIRED = "Only registered clients can manage favorites.";
    private static final String ERROR_ARTISAN_NOT_FOUND_PREFIX = "Artisan not found with id: ";
    private static final String ERROR_FAVORITE_NOT_FOUND_PREFIX = "Favorite not found for artisan: ";
    private static final String ERROR_ALREADY_FAVORITED = "Artisan is already favorited.";
    private static final String ERROR_CAP_REACHED = "Client favorite limit reached.";

    private final CurrentUserProvider currentUserProvider;
    private final ClientRepository clientRepository;
    private final ArtisanRepository artisanRepository;
    private final ClientFavoriteArtisanRepository favoriteRepository;
    private final FavoritesProperties favoritesProperties;
    private final Clock clock;
    private final ViewerPremiumResolver viewerPremiumResolver;

    /**
     * Adds an artisan to the authenticated client's favorites list.
     * <p>
     * <b>Concurrency and Locking Architecture:</b>
     * This method enforces a strict two-lock ordering to guarantee data consistency and prevent deadlocks:
     * <ol>
     *   <li><b>Client Row Lock (Pessimistic Write):</b> Acquired via {@code clientRepository.findWithLockById(user.getId())}.
     *       Serializes all concurrent favorite modifications for the client at the root entity level. Without this lock,
     *       concurrent insertions into an empty favorites collection acquire compatible gap locks on the secondary index,
     *       leading to MariaDB InnoDB deadlocks (Error 1213) when multiple transactions simultaneously request insert intention locks.</li>
     *   <li><b>Client Favorite Rows Lock (Pessimistic Write):</b> Acquired via {@code favoriteRepository.findForUpdateByClientId(client.getId())}.
     *       This locking read performs an InnoDB "current read", bypassing stale MVCC snapshots.
     *       Under default MariaDB REPEATABLE READ isolation, the transaction's MVCC snapshot is established by the very first
     *       statement executed: {@code currentUserProvider.requireCurrentUser()}, which issues a non-locking consistent read
     *       ({@code userRepository.findByEmail(...)}). Any subsequent non-locking read (such as a plain {@code countByClientId})
     *       would read from that frozen snapshot established prior to acquiring the client lock, thereby missing concurrent
     *       commits and violating capacity and duplicate constraints. Using {@code findForUpdateByClientId} executes a locking read
     *       that forces InnoDB to read the latest committed records and accurately enforce the maximum favorite cap and duplicate checks.</li>
     * </ol>
     * Lock ordering must remain strictly client-row first, followed by favorite rows, across the entire application to prevent AB-BA deadlocks.
     *
     * @param artisanId the identifier of the artisan to favorite
     * @return the created favorite response DTO
     * @throws ForbiddenException if the authenticated user is not a registered client
     * @throws ResourceNotFoundException if the artisan does not exist or is not effectively visible
     * @throws ConflictException if the artisan is already favorited or the client's favorite capacity is exceeded
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClientFavoriteArtisanResponseDTO addFavorite(String artisanId) {
        User user = currentUserProvider.requireCurrentUser();
        Client client = clientRepository.findWithLockById(user.getId())
                .orElseThrow(() -> new ForbiddenException(ERROR_CLIENT_PROFILE_REQUIRED));

        LocalDateTime now = LocalDateTime.now(clock);
        Artisan artisan = artisanRepository.findById(artisanId)
                .filter(a -> a.isEffectivelyVisible(now))
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        List<ClientFavoriteArtisan> existingFavorites = favoriteRepository.findForUpdateByClientId(client.getId());
        if (existingFavorites.stream().anyMatch(fav -> fav.getArtisan().getId().equals(artisan.getId()))) {
            throw new ConflictException(ERROR_ALREADY_FAVORITED);
        }
        if (existingFavorites.size() >= favoritesProperties.getMaxPerClient()) {
            throw new ConflictException(ApiErrorCode.FAVORITES_LIMIT_REACHED, ERROR_CAP_REACHED);
        }

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();

        try {
            favorite = favoriteRepository.saveAndFlush(favorite);
        } catch (DataIntegrityViolationException dive) {
            log.warn("Concurrent duplicate favorite insertion for client {} and artisan {}", client.getId(), LogSanitizer.sanitize(artisanId), dive);
            throw new ConflictException(ERROR_ALREADY_FAVORITED, dive);
        }

        return ClientFavoriteArtisanResponseDTO.builder()
                .favoriteId(favorite.getId())
                .artisanId(artisan.getId())
                .favoritedAt(favorite.getCreatedAt())
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PaginatedResponse<ClientFavoriteArtisanItemDTO> listFavorites(Pageable pageable) {
        Client client = requireCurrentClient();

        LocalDateTime now = LocalDateTime.now(clock);
        Page<ClientFavoriteArtisan> page = favoriteRepository.findVisibleByClientId(
                client.getId(),
                AccountStatus.SUSPENDED,
                now,
                pageable
        );

        boolean contactInfoLocked = viewerPremiumResolver.isContactInfoLocked();

        Page<ClientFavoriteArtisanItemDTO> dtoPage = page.map(fav -> ClientFavoriteArtisanItemDTO.builder()
                .favoritedAt(fav.getCreatedAt())
                .artisan(ArtisanDirectoryCardDTO.from(fav.getArtisan(), contactInfoLocked))
                .build());

        return PaginatedResponse.from(dtoPage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FavoriteStatusResponseDTO isFavorited(String artisanId) {
        Client client = requireCurrentClient();

        Artisan artisan = artisanRepository.findById(artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_ARTISAN_NOT_FOUND_PREFIX + artisanId));

        LocalDateTime now = LocalDateTime.now(clock);
        if (!artisan.isEffectivelyVisible(now)) {
            return FavoriteStatusResponseDTO.of(false);
        }

        boolean favorited = favoriteRepository.existsByClientIdAndArtisanId(client.getId(), artisanId);
        return FavoriteStatusResponseDTO.of(favorited);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFavorite(String artisanId) {
        Client client = requireCurrentClient();

        ClientFavoriteArtisan favorite = favoriteRepository.findByClientIdAndArtisanId(client.getId(), artisanId)
                .orElseThrow(() -> new ResourceNotFoundException(ERROR_FAVORITE_NOT_FOUND_PREFIX + artisanId));

        favoriteRepository.delete(favorite);
    }

    private Client requireCurrentClient() {
        User user = currentUserProvider.requireCurrentUser();
        return clientRepository.findById(user.getId())
                .orElseThrow(() -> new ForbiddenException(ERROR_CLIENT_PROFILE_REQUIRED));
    }
}
