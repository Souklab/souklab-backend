package com.project.souklab.integration.favorite;

import com.project.souklab.config.FavoritesProperties;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ClientFavoriteArtisanRepository;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.favorite.ClientFavoriteArtisanItemDTO;
import com.project.souklab.dto.favorite.ClientFavoriteArtisanResponseDTO;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientFavoriteArtisan;
import com.project.souklab.model.User;
import com.project.souklab.security.Permission;
import com.project.souklab.service.favorite.ArtisanFavoriteService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.SessionFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.project.souklab.controller.support.SecurityTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live MariaDB integration tests verifying database constraints, foreign key cascade rules,
 * soft-delete and suspension filtering, concurrent double-add serialization,
 * pessimistic-lock cap enforcement, and zero N+1 query execution.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.search.enabled=false"
})
class ClientFavoriteArtisanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ArtisanRepository artisanRepository;

    @Autowired
    private ClientFavoriteArtisanRepository favoriteRepository;

    @Autowired
    private ArtisanFavoriteService artisanFavoriteService;

    @Autowired
    private FavoritesProperties favoritesProperties;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private int originalCap;
    private final List<String> createdFavoriteIds = new ArrayList<>();
    private final List<String> createdClientIds = new ArrayList<>();
    private final List<String> createdArtisanIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        originalCap = favoritesProperties.getMaxPerClient();
    }

    @AfterEach
    void tearDown() {
        favoritesProperties.setMaxPerClient(originalCap);
        SecurityContextHolder.clearContext();

        transactionTemplate.execute(status -> {
            for (String favId : createdFavoriteIds) {
                entityManager.createNativeQuery("DELETE FROM client_favorite_artisans WHERE id = :id")
                        .setParameter("id", favId)
                        .executeUpdate();
            }
            for (String clientId : createdClientIds) {
                entityManager.createNativeQuery("DELETE FROM clients WHERE id = :id")
                        .setParameter("id", clientId)
                        .executeUpdate();
            }
            for (String artisanId : createdArtisanIds) {
                entityManager.createNativeQuery("DELETE FROM artisans WHERE id = :id")
                        .setParameter("id", artisanId)
                        .executeUpdate();
            }
            for (String userId : createdUserIds) {
                entityManager.createNativeQuery("DELETE FROM users WHERE id = :id")
                        .setParameter("id", userId)
                        .executeUpdate();
            }
            return null;
        });
    }

    private User createTestUser(String emailPrefix, AccountStatus status) {
        return transactionTemplate.execute(statusTx -> {
            String id = UUID.randomUUID().toString();
            User user = User.builder()
                    .email(emailPrefix + "-" + UUID.randomUUID() + "@souklab.dz")
                    .firstName("Test")
                    .lastName("User")
                    .status(status)
                    .emailVerified(true)
                    .build();
            user.setId(id);
            entityManager.persist(user);
            entityManager.flush();
            createdUserIds.add(user.getId());
            return user;
        });
    }

    private Client createTestClient(User user) {
        return transactionTemplate.execute(statusTx -> {
            User managedUser = entityManager.find(User.class, user.getId());
            Client client = Client.builder()
                    .user(managedUser)
                    .build();
            entityManager.persist(client);
            managedUser.setClient(client);
            entityManager.flush();
            createdClientIds.add(client.getId());
            return client;
        });
    }

    private Artisan createTestArtisan(User user, boolean verified, LocalDateTime deletedAt) {
        return transactionTemplate.execute(statusTx -> {
            User managedUser = entityManager.find(User.class, user.getId());
            Artisan artisan = Artisan.builder()
                    .id(managedUser.getId())
                    .user(managedUser)
                    .bio("Artisan bio")
                    .city("Algiers")
                    .isVerified(verified)
                    .rating(4.5)
                    .reviewsCount(5)
                    .viewsCount(100)
                    .build();
            artisan.setDeletedAt(deletedAt);
            entityManager.persist(artisan);
            managedUser.setArtisan(artisan);
            entityManager.flush();
            createdArtisanIds.add(artisan.getId());
            return artisan;
        });
    }

    @Test
    @DisplayName("Unique constraint: duplicate (client_id, artisan_id) triggers DataIntegrityViolationException")
    void uniqueConstraint_duplicateEntryThrowsException() {
        User clientUser = createTestUser("client-uniq", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-uniq", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan favorite1 = ClientFavoriteArtisan.builder()
                    .client(managedClient)
                    .artisan(managedArtisan)
                    .build();
            favorite1.ensureId();
            entityManager.persist(favorite1);
            entityManager.flush();
            createdFavoriteIds.add(favorite1.getId());
            return null;
        });

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan favorite2 = ClientFavoriteArtisan.builder()
                    .client(managedClient)
                    .artisan(managedArtisan)
                    .build();
            favorite2.ensureId();
            entityManager.persist(favorite2);
            entityManager.flush();
            return null;
        })).isInstanceOfAny(
                DataIntegrityViolationException.class,
                ConstraintViolationException.class,
                PersistenceException.class
        );
    }

    @Test
    @DisplayName("FK cascade: physical delete of client cascades to client_favorite_artisans")
    void cascadeDelete_clientDeleteCascadesToFavorites() {
        User clientUser = createTestUser("client-casc", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-casc", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        String favId = transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                    .client(managedClient)
                    .artisan(managedArtisan)
                    .build();
            favorite.ensureId();
            entityManager.persist(favorite);
            entityManager.flush();
            createdFavoriteIds.add(favorite.getId());
            return favorite.getId();
        });

        transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("DELETE FROM clients WHERE id = :id")
                    .setParameter("id", client.getId())
                    .executeUpdate();
            return null;
        });

        Optional<ClientFavoriteArtisan> lookup = favoriteRepository.findById(favId);
        assertThat(lookup).isEmpty();
    }

    @Test
    @DisplayName("FK cascade: physical delete of artisan cascades to client_favorite_artisans")
    void cascadeDelete_artisanDeleteCascadesToFavorites() {
        User clientUser = createTestUser("client-casc-art", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-casc-art", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        String favId = transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan favorite = ClientFavoriteArtisan.builder()
                    .client(managedClient)
                    .artisan(managedArtisan)
                    .build();
            favorite.ensureId();
            entityManager.persist(favorite);
            entityManager.flush();
            createdFavoriteIds.add(favorite.getId());
            return favorite.getId();
        });

        transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("DELETE FROM artisans WHERE id = :id")
                    .setParameter("id", artisan.getId())
                    .executeUpdate();
            return null;
        });

        Optional<ClientFavoriteArtisan> lookup = favoriteRepository.findById(favId);
        assertThat(lookup).isEmpty();
    }

    @Test
    @DisplayName("List filtering: soft-deleted and suspended artisans are excluded from visible favorites")
    void listFiltering_excludesSoftDeletedAndSuspendedArtisans() {
        User clientUser = createTestUser("client-filt", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User visibleUser = createTestUser("artisan-vis", AccountStatus.ACTIVE);
        Artisan visibleArtisan = createTestArtisan(visibleUser, true, null);

        User deletedUser = createTestUser("artisan-del", AccountStatus.ACTIVE);
        Artisan deletedArtisan = createTestArtisan(deletedUser, true, LocalDateTime.now().minusDays(1));

        User suspendedUser = createTestUser("artisan-susp", AccountStatus.SUSPENDED);
        Artisan suspendedArtisan = createTestArtisan(suspendedUser, true, null);

        transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedVis = entityManager.find(Artisan.class, visibleArtisan.getId());
            Artisan managedDel = entityManager.find(Artisan.class, deletedArtisan.getId());
            Artisan managedSusp = entityManager.find(Artisan.class, suspendedArtisan.getId());

            ClientFavoriteArtisan fav1 = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedVis).build();
            fav1.ensureId();
            entityManager.persist(fav1);
            createdFavoriteIds.add(fav1.getId());

            ClientFavoriteArtisan fav2 = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedDel).build();
            fav2.ensureId();
            entityManager.persist(fav2);
            createdFavoriteIds.add(fav2.getId());

            ClientFavoriteArtisan fav3 = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedSusp).build();
            fav3.ensureId();
            entityManager.persist(fav3);
            createdFavoriteIds.add(fav3.getId());

            entityManager.flush();
            return null;
        });

        Page<ClientFavoriteArtisan> page = favoriteRepository.findVisibleByClientId(
                client.getId(),
                AccountStatus.SUSPENDED,
                LocalDateTime.now(),
                PageRequest.of(0, 10)
        );

        assertThat(page.getTotalElements()).isEqualTo(1L);
        assertThat(page.getContent().getFirst().getArtisan().getId()).isEqualTo(visibleArtisan.getId());
    }

    @Test
    @DisplayName("Concurrent double-add: exactly one thread succeeds (201) and one fails (409)")
    void concurrentDoubleAdd_exactlyOneSucceedsAndOneThrows409() throws InterruptedException {
        User clientUser = createTestUser("client-race", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-race", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        clientUser.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
                SecurityContextHolder.setContext(context);

                try {
                    ClientFavoriteArtisanResponseDTO response = artisanFavoriteService.addFavorite(artisan.getId());
                    if (response != null) {
                        successCount.incrementAndGet();
                        createdFavoriteIds.add(response.getFavoriteId());
                    }
                } catch (ConflictException ce) {
                    conflictCount.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ignored) {
                /**
                 * Checked exceptions are captured in atomic counters.
                 */
            }
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);
        assertThat(favoriteRepository.countByClientId(client.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("Cap under concurrency: pessimistic lock restricts inserts to configured cap")
    void capUnderConcurrency_enforcesCapStrictly() throws InterruptedException {
        favoritesProperties.setMaxPerClient(2);

        User clientUser = createTestUser("client-cap", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        int totalThreads = 5;
        List<Artisan> artisans = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            User artisanUser = createTestUser("artisan-cap-" + i, AccountStatus.ACTIVE);
            artisans.add(createTestArtisan(artisanUser, true, null));
        }

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < totalThreads; i++) {
            final String targetArtisanId = artisans.get(i).getId();
            tasks.add(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        clientUser.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
                SecurityContextHolder.setContext(context);

                try {
                    ClientFavoriteArtisanResponseDTO response = artisanFavoriteService.addFavorite(targetArtisanId);
                    if (response != null) {
                        successCount.incrementAndGet();
                        createdFavoriteIds.add(response.getFavoriteId());
                    }
                } catch (ConflictException ce) {
                    conflictCount.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(executor.submit(task));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException ignored) {
                /**
                 * Checked exceptions are captured in atomic counters.
                 */
            }
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount.get()).isEqualTo(2);
        assertThat(conflictCount.get()).isEqualTo(3);
        assertThat(favoriteRepository.countByClientId(client.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("Query count assertion: listFavorites uses bounded batch queries with zero N+1")
    void queryCountAssertion_provesZeroNPlusOne() {
        User clientUser = createTestUser("client-nplus1", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        for (int i = 0; i < 5; i++) {
            User artisanUser = createTestUser("artisan-nplus1-" + i, AccountStatus.ACTIVE);
            Artisan artisan = createTestArtisan(artisanUser, true, null);
            transactionTemplate.execute(status -> {
                Client managedClient = entityManager.find(Client.class, client.getId());
                Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
                ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedArtisan).build();
                fav.ensureId();
                entityManager.persist(fav);
                entityManager.flush();
                createdFavoriteIds.add(fav.getId());
                return null;
            });
        }

        transactionTemplate.execute(status -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    clientUser.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
            SecurityContextHolder.setContext(context);

            SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
            Statistics stats = sessionFactory.getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();

            PaginatedResponse<ClientFavoriteArtisanItemDTO> response = artisanFavoriteService.listFavorites(PageRequest.of(0, 20));

            assertThat(response.getContent()).hasSize(5);
            long queryCount = stats.getPrepareStatementCount();
            assertThat(queryCount).isLessThanOrEqualTo(14L);
            return null;
        });
    }

    @Test
    @DisplayName("N+1 prevention: addFavorite executes identical bounded queries for 5 vs 50 existing favorites")
    void addFavorite_duplicateCheckDoesNotTriggerNPlusOneQueries() {
        /**
         * Case 1: Client with 5 existing favorites adding a 6th.
         */
        User clientUser5 = createTestUser("client-q5", AccountStatus.ACTIVE);
        Client client5 = createTestClient(clientUser5);

        for (int i = 0; i < 5; i++) {
            User aUser = createTestUser("artisan-q5-" + i, AccountStatus.ACTIVE);
            Artisan a = createTestArtisan(aUser, true, null);
            transactionTemplate.execute(status -> {
                Client managedClient = entityManager.find(Client.class, client5.getId());
                Artisan managedArtisan = entityManager.find(Artisan.class, a.getId());
                ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedArtisan).build();
                entityManager.persist(fav);
                entityManager.flush();
                createdFavoriteIds.add(fav.getId());
                return null;
            });
        }

        User targetArtisanUser5 = createTestUser("artisan-target5", AccountStatus.ACTIVE);
        Artisan targetArtisan5 = createTestArtisan(targetArtisanUser5, true, null);

        long queryCountFor5 = transactionTemplate.execute(status -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    clientUser5.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
            SecurityContextHolder.setContext(context);

            SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
            Statistics stats = sessionFactory.getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();

            ClientFavoriteArtisanResponseDTO response = artisanFavoriteService.addFavorite(targetArtisan5.getId());
            createdFavoriteIds.add(response.getFavoriteId());

            return stats.getPrepareStatementCount();
        });

        /**
         * Case 2: Client with 50 existing favorites adding a 51st.
         */
        User clientUser50 = createTestUser("client-q50", AccountStatus.ACTIVE);
        Client client50 = createTestClient(clientUser50);

        for (int i = 0; i < 50; i++) {
            User aUser = createTestUser("artisan-q50-" + i, AccountStatus.ACTIVE);
            Artisan a = createTestArtisan(aUser, true, null);
            transactionTemplate.execute(status -> {
                Client managedClient = entityManager.find(Client.class, client50.getId());
                Artisan managedArtisan = entityManager.find(Artisan.class, a.getId());
                ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedArtisan).build();
                entityManager.persist(fav);
                entityManager.flush();
                createdFavoriteIds.add(fav.getId());
                return null;
            });
        }

        User targetArtisanUser50 = createTestUser("artisan-target50", AccountStatus.ACTIVE);
        Artisan targetArtisan50 = createTestArtisan(targetArtisanUser50, true, null);

        long queryCountFor50 = transactionTemplate.execute(status -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    clientUser50.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
            SecurityContextHolder.setContext(context);

            SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
            Statistics stats = sessionFactory.getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();

            ClientFavoriteArtisanResponseDTO response = artisanFavoriteService.addFavorite(targetArtisan50.getId());
            createdFavoriteIds.add(response.getFavoriteId());

            return stats.getPrepareStatementCount();
        });

        assertThat(queryCountFor50)
                .withFailMessage("Expected identical query counts for 5 vs 50 existing favorites, but got %d vs %d", queryCountFor5, queryCountFor50)
                .isEqualTo(queryCountFor5);
    }

    @Test
    @DisplayName("List masking: non-premium client sees masked name, premium client sees full name")
    void listFavorites_maskingBehavior_nonPremiumClientReceivesMaskedName_premiumReceivesFullName() {
        User clientUser = createTestUser("client-mask", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-mask", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        transactionTemplate.execute(status -> {
            User managedArtisanUser = entityManager.find(User.class, artisanUser.getId());
            managedArtisanUser.setFirstName("Reda");
            managedArtisanUser.setLastName("Boutique");
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder().client(managedClient).artisan(managedArtisan).build();
            fav.ensureId();
            entityManager.persist(fav);
            entityManager.flush();
            createdFavoriteIds.add(fav.getId());
            return null;
        });

        transactionTemplate.execute(status -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    clientUser.getEmail(), "N/A", List.of(Permission.Client.FAVORITES)));
            SecurityContextHolder.setContext(context);

            PaginatedResponse<ClientFavoriteArtisanItemDTO> nonPremiumResponse = artisanFavoriteService.listFavorites(PageRequest.of(0, 10));
            assertThat(nonPremiumResponse.getContent()).hasSize(1);
            String maskedName = nonPremiumResponse.getContent().getFirst().getArtisan().getArtisanName();
            assertThat(maskedName).startsWith("Artisan #");
            assertThat(maskedName).doesNotContain("Reda");

            Client managedClient = entityManager.find(Client.class, client.getId());
            managedClient.setPremium(true);
            entityManager.flush();

            PaginatedResponse<ClientFavoriteArtisanItemDTO> premiumResponse = artisanFavoriteService.listFavorites(PageRequest.of(0, 10));
            assertThat(premiumResponse.getContent()).hasSize(1);
            String unmaskedName = premiumResponse.getContent().getFirst().getArtisan().getArtisanName();
            assertThat(unmaskedName).isEqualTo("Reda Boutique");
            return null;
        });
    }

    /**
     * Helper to batch create active artisans with linked user accounts.
     */
    private List<Artisan> createTestArtisans(String prefix, int count) {
        return transactionTemplate.execute(status -> {
            List<Artisan> artisans = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = UUID.randomUUID().toString();
                User user = User.builder()
                        .email(prefix + "-" + i + "-" + UUID.randomUUID() + "@souklab.dz")
                        .firstName("ArtisanFirst" + i)
                        .lastName("ArtisanLast" + i)
                        .status(AccountStatus.ACTIVE)
                        .emailVerified(true)
                        .build();
                user.setId(id);
                entityManager.persist(user);
                createdUserIds.add(user.getId());

                Artisan artisan = Artisan.builder()
                        .id(id)
                        .user(user)
                        .bio("Bio for artisan " + i)
                        .city("Algiers")
                        .isVerified(true)
                        .rating(4.5)
                        .reviewsCount(5)
                        .viewsCount(100)
                        .build();
                entityManager.persist(artisan);
                user.setArtisan(artisan);
                createdArtisanIds.add(artisan.getId());
                artisans.add(artisan);
            }
            entityManager.flush();
            return artisans;
        });
    }

    /**
     * Helper to batch create favorite records for a client.
     */
    private void addFavoritesForClient(Client client, List<Artisan> artisans) {
        transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            for (Artisan artisan : artisans) {
                Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
                ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder()
                        .client(managedClient)
                        .artisan(managedArtisan)
                        .build();
                fav.ensureId();
                entityManager.persist(fav);
                createdFavoriteIds.add(fav.getId());
            }
            entityManager.flush();
            return null;
        });
    }

    /**
     * Verifies that prepared statement count remains constant across 5, 20, and 60 favorites under default
     * pagination (size=20), and increases predictably when requesting size=100 due to batch-splitting across collections.
     */
    @Test
    @DisplayName("Statement count verification: constant across 5, 20, 60 favorites under default size=20; splits batch at size=100")
    void listFavorites_statementCountVerification_constantUnderDefaultPaginationAndSplitsBatchAt100() throws Exception {
        User clientUser5 = createTestUser("client-sc5", AccountStatus.ACTIVE);
        Client client5 = createTestClient(clientUser5);
        List<Artisan> artisans5 = createTestArtisans("art-sc5", 5);
        addFavoritesForClient(client5, artisans5);

        User clientUser20 = createTestUser("client-sc20", AccountStatus.ACTIVE);
        Client client20 = createTestClient(clientUser20);
        List<Artisan> artisans20 = createTestArtisans("art-sc20", 20);
        addFavoritesForClient(client20, artisans20);

        User clientUser60 = createTestUser("client-sc60", AccountStatus.ACTIVE);
        Client client60 = createTestClient(clientUser60);
        List<Artisan> artisans60 = createTestArtisans("art-sc60", 60);
        addFavoritesForClient(client60, artisans60);

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .with(SecurityTestUtils.client(clientUser5.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content.length()").value(5));
        long count5 = stats.getPrepareStatementCount();

        stats.clear();
        mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .with(SecurityTestUtils.client(clientUser20.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(20))
                .andExpect(jsonPath("$.data.content.length()").value(20));
        long count20 = stats.getPrepareStatementCount();

        stats.clear();
        mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .with(SecurityTestUtils.client(clientUser60.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(60))
                .andExpect(jsonPath("$.data.content.length()").value(20));
        long count60 = stats.getPrepareStatementCount();

        stats.clear();
        mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .param("size", "100")
                        .with(SecurityTestUtils.client(clientUser60.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(60))
                .andExpect(jsonPath("$.data.content.length()").value(60));
        long count60Size100 = stats.getPrepareStatementCount();

        System.out.printf("BATCH_E_STATEMENT_COUNTS: count5=%d, count20=%d, count60=%d, count60Size100=%d%n",
                count5, count20, count60, count60Size100);

        assertEquals(count20, count60,
                String.format("Statement count for 60 favorites under default size=20 (%d) must equal statement count for 20 favorites (%d)",
                        count60, count20));
        assertEquals(13L, count5,
                String.format("Statement count for 5 favorites was %d", count5));
        assertEquals(29L, count20,
                String.format("Statement count for 20 favorites was %d", count20));
        assertEquals(71L, count60Size100,
                String.format("Statement count for 60 favorites with size=100 was %d", count60Size100));

        long delta = count60Size100 - count60;
        assertEquals(42L, delta,
                String.format("Statement count for 60 favorites with size=100 (%d) exceeds default page size=20 count (%d) by literal delta=%d",
                        count60Size100, count60, delta));
    }

    /**
     * Verifies full-stack HTTP masking behavior via MockMvc: non-premium client receives anonymized name,
     * which dynamically transitions to real name upon premium upgrade in the database.
     */
    @Test
    @DisplayName("HTTP masking: non-premium client receives masked name; unmasks dynamically when upgraded to premium in DB")
    void listFavorites_httpMasking_masksNonPremiumAndUnmasksWhenClientBecomesPremium() throws Exception {
        User clientUser = createTestUser("client-http-mask", AccountStatus.ACTIVE);
        Client client = createTestClient(clientUser);

        User artisanUser = createTestUser("artisan-http-mask", AccountStatus.ACTIVE);
        Artisan artisan = createTestArtisan(artisanUser, true, null);

        transactionTemplate.execute(status -> {
            User managedArtisanUser = entityManager.find(User.class, artisanUser.getId());
            managedArtisanUser.setFirstName("Fatima");
            managedArtisanUser.setLastName("Zohra");
            Client managedClient = entityManager.find(Client.class, client.getId());
            Artisan managedArtisan = entityManager.find(Artisan.class, artisan.getId());
            ClientFavoriteArtisan fav = ClientFavoriteArtisan.builder()
                    .client(managedClient)
                    .artisan(managedArtisan)
                    .build();
            fav.ensureId();
            entityManager.persist(fav);
            entityManager.flush();
            createdFavoriteIds.add(fav.getId());
            return null;
        });

        String expectedMaskedName = "Artisan #" + artisan.getId().substring(artisan.getId().length() - 5).toUpperCase(Locale.ROOT);
        String maskedBody = mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .with(SecurityTestUtils.client(clientUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].artisan.artisanName").value(expectedMaskedName))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(maskedBody).doesNotContain("Fatima");
        assertThat(maskedBody).doesNotContain("Zohra");

        transactionTemplate.execute(status -> {
            Client managedClient = entityManager.find(Client.class, client.getId());
            managedClient.setPremium(true);
            entityManager.flush();
            return null;
        });

        mockMvc.perform(get("/api/v1/client/favorites/artisans")
                        .with(SecurityTestUtils.client(clientUser.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].artisan.artisanName").value("Fatima Zohra"));
    }
}
