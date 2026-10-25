package com.project.souklab.service.favorite;

import com.project.souklab.config.FavoritesProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ClientFavoriteArtisanRepository;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dto.common.ApiErrorCode;
import com.project.souklab.dto.common.PaginatedResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying business rules, validations, error mapping, and concurrency guards
 * for client artisan favorites management.
 */
@ExtendWith(MockitoExtension.class)
class ArtisanFavoriteServiceTest {

    private static final String CLIENT_USER_ID = "client-user-1";
    private static final String ARTISAN_ID = "artisan-1";
    private static final String FAVORITE_ID = "fav-uuid-1";

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private ArtisanRepository artisanRepository;

    @Mock
    private ClientFavoriteArtisanRepository favoriteRepository;

    @Mock
    private ViewerPremiumResolver viewerPremiumResolver;

    private FavoritesProperties favoritesProperties;
    private Clock clock;
    private ArtisanFavoriteService service;

    private User clientUser;
    private Client client;
    private User artisanUser;
    private Artisan artisan;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-23T10:15:00Z"), ZoneOffset.UTC);
        favoritesProperties = new FavoritesProperties();
        favoritesProperties.setMaxPerClient(500);

        service = new ArtisanFavoriteServiceImpl(
                currentUserProvider,
                clientRepository,
                artisanRepository,
                favoriteRepository,
                favoritesProperties,
                clock,
                viewerPremiumResolver
        );

        clientUser = User.builder()
                .email("client@souklab.dz")
                .firstName("Client")
                .lastName("User")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        clientUser.setId(CLIENT_USER_ID);

        client = Client.builder()
                .user(clientUser)
                .companyName("Test Client Co")
                .build();
        client.setId(CLIENT_USER_ID);

        artisanUser = User.builder()
                .email("artisan@souklab.dz")
                .firstName("Karim")
                .lastName("Benali")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        artisanUser.setId("artisan-user-1");

        artisan = Artisan.builder()
                .id(ARTISAN_ID)
                .user(artisanUser)
                .bio("Master woodworker with 20 years experience.")
                .city("Algiers")
                .rating(4.8)
                .reviewsCount(15)
                .viewsCount(230)
                .isVerified(true)
                .materials(new HashSet<>())
                .techniques(new HashSet<>())
                .galleryImages(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("addFavorite: adds an artisan to favorites successfully")
    void addFavorite_success() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        when(favoriteRepository.findForUpdateByClientId(CLIENT_USER_ID)).thenReturn(List.of());
        when(favoriteRepository.saveAndFlush(any(ClientFavoriteArtisan.class))).thenAnswer(invocation -> {
            ClientFavoriteArtisan fav = invocation.getArgument(0);
            fav.setId(FAVORITE_ID);
            fav.setCreatedAt(LocalDateTime.now(clock));
            return fav;
        });

        ClientFavoriteArtisanResponseDTO response = service.addFavorite(ARTISAN_ID);

        assertThat(response).isNotNull();
        assertThat(response.getFavoriteId()).isEqualTo(FAVORITE_ID);
        assertThat(response.getArtisanId()).isEqualTo(ARTISAN_ID);
        assertThat(response.getFavoritedAt()).isEqualTo(LocalDateTime.now(clock));
        verify(clientRepository).findWithLockById(CLIENT_USER_ID);
        verify(favoriteRepository).saveAndFlush(any(ClientFavoriteArtisan.class));
    }

    @Test
    @DisplayName("addFavorite: throws ForbiddenException when authenticated caller has no Client profile")
    void addFavorite_callerWithoutClientProfile_throwsForbiddenException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only registered clients can manage favorites.");

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: throws ResourceNotFoundException when artisan does not exist")
    void addFavorite_artisanNotFound_throwsResourceNotFoundException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById("non-existent-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addFavorite("non-existent-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: non-existent-id");

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: throws ResourceNotFoundException when artisan is soft-deleted")
    void addFavorite_artisanSoftDeleted_throwsResourceNotFoundException() {
        artisan.setDeletedAt(LocalDateTime.now(clock).minusDays(1));
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: " + ARTISAN_ID);

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: throws ResourceNotFoundException when artisan user is suspended")
    void addFavorite_artisanUserSuspended_throwsResourceNotFoundException() {
        artisanUser.setStatus(AccountStatus.SUSPENDED);
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: " + ARTISAN_ID);

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: throws ConflictException when artisan is already favorited")
    void addFavorite_alreadyFavorited_throwsConflictException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        ClientFavoriteArtisan existingFav = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        when(favoriteRepository.findForUpdateByClientId(CLIENT_USER_ID)).thenReturn(List.of(existingFav));

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Artisan is already favorited.");

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: throws ConflictException when client favorite cap is reached")
    void addFavorite_capReached_throwsConflictException() {
        favoritesProperties.setMaxPerClient(3);
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        Artisan other1 = Artisan.builder().id("other-1").build();
        Artisan other2 = Artisan.builder().id("other-2").build();
        Artisan other3 = Artisan.builder().id("other-3").build();
        List<ClientFavoriteArtisan> existingList = List.of(
                ClientFavoriteArtisan.builder().artisan(other1).build(),
                ClientFavoriteArtisan.builder().artisan(other2).build(),
                ClientFavoriteArtisan.builder().artisan(other3).build()
        );
        when(favoriteRepository.findForUpdateByClientId(CLIENT_USER_ID)).thenReturn(existingList);

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getErrorCode()).isEqualTo(ApiErrorCode.FAVORITES_LIMIT_REACHED.value()))
                .hasMessage("Client favorite limit reached.");

        verify(favoriteRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("addFavorite: translates DataIntegrityViolationException on concurrent race to ConflictException")
    void addFavorite_concurrentRace_throwsConflictException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findWithLockById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        when(favoriteRepository.findForUpdateByClientId(CLIENT_USER_ID)).thenReturn(List.of());
        when(favoriteRepository.saveAndFlush(any(ClientFavoriteArtisan.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        assertThatThrownBy(() -> service.addFavorite(ARTISAN_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Artisan is already favorited.");
    }

    @Test
    @DisplayName("listFavorites: returns paginated items mapped to directory card shape")
    void listFavorites_success() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        favorite.setCreatedAt(LocalDateTime.now(clock).minusHours(1));

        Pageable pageable = PageRequest.of(0, 20);
        when(favoriteRepository.findVisibleByClientId(
                eq(CLIENT_USER_ID),
                eq(AccountStatus.SUSPENDED),
                eq(LocalDateTime.now(clock)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(favorite), pageable, 1L));

        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(false);

        PaginatedResponse<ClientFavoriteArtisanItemDTO> result = service.listFavorites(pageable);

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getContent()).hasSize(1);
        ClientFavoriteArtisanItemDTO item = result.getContent().getFirst();
        assertThat(item.getFavoritedAt()).isEqualTo(favorite.getCreatedAt());
        assertThat(item.getArtisan()).isNotNull();
        assertThat(item.getArtisan().getId()).isEqualTo(ARTISAN_ID);
        assertThat(item.getArtisan().getArtisanName()).isEqualTo("Karim Benali");
    }

    @Test
    @DisplayName("listFavorites: non-premium client sees masked artisan name")
    void listFavorites_whenClientIsNonPremium_masksArtisanName() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(true);

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        favorite.setCreatedAt(LocalDateTime.now(clock).minusHours(1));

        Pageable pageable = PageRequest.of(0, 20);
        when(favoriteRepository.findVisibleByClientId(
                eq(CLIENT_USER_ID),
                eq(AccountStatus.SUSPENDED),
                eq(LocalDateTime.now(clock)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(favorite), pageable, 1L));

        PaginatedResponse<ClientFavoriteArtisanItemDTO> result = service.listFavorites(pageable);

        assertThat(result).isNotNull();
        ClientFavoriteArtisanItemDTO item = result.getContent().getFirst();
        assertThat(item.getArtisan().getArtisanName()).startsWith("Artisan #");
        assertThat(item.getArtisan().getArtisanName()).doesNotContain("Karim Benali");
    }

    @Test
    @DisplayName("listFavorites: premium client sees full unmasked artisan name")
    void listFavorites_whenClientIsPremium_showsFullName() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(false);

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        favorite.setCreatedAt(LocalDateTime.now(clock).minusHours(1));

        Pageable pageable = PageRequest.of(0, 20);
        when(favoriteRepository.findVisibleByClientId(
                eq(CLIENT_USER_ID),
                eq(AccountStatus.SUSPENDED),
                eq(LocalDateTime.now(clock)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(favorite), pageable, 1L));

        PaginatedResponse<ClientFavoriteArtisanItemDTO> result = service.listFavorites(pageable);

        assertThat(result).isNotNull();
        ClientFavoriteArtisanItemDTO item = result.getContent().getFirst();
        assertThat(item.getArtisan().getArtisanName()).isEqualTo("Karim Benali");
    }

    @Test
    @DisplayName("listFavorites: output follows dynamic premium status changes between calls")
    void listFavorites_whenPremiumStatusChangesBetweenCalls_outputFollows() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(viewerPremiumResolver.isContactInfoLocked()).thenReturn(true, false);

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        favorite.setCreatedAt(LocalDateTime.now(clock).minusHours(1));

        Pageable pageable = PageRequest.of(0, 20);
        when(favoriteRepository.findVisibleByClientId(
                eq(CLIENT_USER_ID),
                eq(AccountStatus.SUSPENDED),
                eq(LocalDateTime.now(clock)),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(favorite), pageable, 1L));

        PaginatedResponse<ClientFavoriteArtisanItemDTO> firstResult = service.listFavorites(pageable);
        assertThat(firstResult.getContent().getFirst().getArtisan().getArtisanName()).startsWith("Artisan #");

        PaginatedResponse<ClientFavoriteArtisanItemDTO> secondResult = service.listFavorites(pageable);
        assertThat(secondResult.getContent().getFirst().getArtisan().getArtisanName()).isEqualTo("Karim Benali");
    }

    @Test
    @DisplayName("listFavorites: throws ForbiddenException when caller lacks Client profile")
    void listFavorites_callerWithoutClientProfile_throwsForbiddenException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listFavorites(PageRequest.of(0, 20)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only registered clients can manage favorites.");
    }

    @Test
    @DisplayName("isFavorited: returns true when artisan is visible and favorited")
    void isFavorited_whenVisibleAndFavorited_returnsTrue() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        when(favoriteRepository.existsByClientIdAndArtisanId(CLIENT_USER_ID, ARTISAN_ID)).thenReturn(true);

        FavoriteStatusResponseDTO status = service.isFavorited(ARTISAN_ID);

        assertThat(status).isNotNull();
        assertThat(status.isFavorited()).isTrue();
    }

    @Test
    @DisplayName("isFavorited: returns false when artisan is visible but not favorited")
    void isFavorited_whenVisibleAndNotFavorited_returnsFalse() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));
        when(favoriteRepository.existsByClientIdAndArtisanId(CLIENT_USER_ID, ARTISAN_ID)).thenReturn(false);

        FavoriteStatusResponseDTO status = service.isFavorited(ARTISAN_ID);

        assertThat(status).isNotNull();
        assertThat(status.isFavorited()).isFalse();
    }

    @Test
    @DisplayName("isFavorited: returns false when artisan is hidden, never 404 for existing artisan")
    void isFavorited_whenHiddenAndFavorited_returnsFalse() {
        artisanUser.setStatus(AccountStatus.SUSPENDED);
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById(ARTISAN_ID)).thenReturn(Optional.of(artisan));

        FavoriteStatusResponseDTO status = service.isFavorited(ARTISAN_ID);

        assertThat(status).isNotNull();
        assertThat(status.isFavorited()).isFalse();
        verify(favoriteRepository, never()).existsByClientIdAndArtisanId(any(), any());
    }

    @Test
    @DisplayName("isFavorited: throws ResourceNotFoundException when artisan does not exist in DB")
    void isFavorited_whenArtisanNotFound_throwsResourceNotFoundException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(artisanRepository.findById("missing-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.isFavorited("missing-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Artisan not found with id: missing-id");
    }

    @Test
    @DisplayName("isFavorited: throws ForbiddenException when caller lacks Client profile")
    void isFavorited_callerWithoutClientProfile_throwsForbiddenException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.isFavorited(ARTISAN_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only registered clients can manage favorites.");
    }

    @Test
    @DisplayName("removeFavorite: removes existing favorite successfully")
    void removeFavorite_success() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        when(favoriteRepository.findByClientIdAndArtisanId(CLIENT_USER_ID, ARTISAN_ID))
                .thenReturn(Optional.of(favorite));

        service.removeFavorite(ARTISAN_ID);

        verify(favoriteRepository).delete(favorite);
    }

    @Test
    @DisplayName("removeFavorite: allows removing favorite for a currently hidden artisan")
    void removeFavorite_hiddenArtisan_success() {
        artisanUser.setStatus(AccountStatus.SUSPENDED);
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));

        ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                .client(client)
                .artisan(artisan)
                .build();
        favorite.setId(FAVORITE_ID);
        when(favoriteRepository.findByClientIdAndArtisanId(CLIENT_USER_ID, ARTISAN_ID))
                .thenReturn(Optional.of(favorite));

        service.removeFavorite(ARTISAN_ID);

        verify(favoriteRepository).delete(favorite);
    }

    @Test
    @DisplayName("removeFavorite: throws ResourceNotFoundException when favorite record does not exist")
    void removeFavorite_favoriteNotFound_throwsResourceNotFoundException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.of(client));
        when(favoriteRepository.findByClientIdAndArtisanId(CLIENT_USER_ID, ARTISAN_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeFavorite(ARTISAN_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Favorite not found for artisan: " + ARTISAN_ID);

        verify(favoriteRepository, never()).delete(any());
    }

    @Test
    @DisplayName("removeFavorite: throws ForbiddenException when caller lacks Client profile")
    void removeFavorite_callerWithoutClientProfile_throwsForbiddenException() {
        when(currentUserProvider.requireCurrentUser()).thenReturn(clientUser);
        when(clientRepository.findById(CLIENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeFavorite(ARTISAN_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Only registered clients can manage favorites.");

        verify(favoriteRepository, never()).delete(any());
    }
}
