package com.project.souklab.controller.directory;

import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.EpoqueRepository;
import com.project.souklab.dao.JobCategoryRepository;
import com.project.souklab.dao.JobSubCategoryRepository;
import com.project.souklab.dao.MaterialRepository;
import com.project.souklab.dao.RegionRepository;
import com.project.souklab.dao.TechniqueRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AccountRole;
import com.project.souklab.model.ArtisanSubscription;
import com.project.souklab.model.BillingPeriod;
import com.project.souklab.model.SubscriptionStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live database integration test suite verifying public artisan directory search and faceted discovery.
 * Exercises {@code GET /api/v1/public/directory} against relational persistence, validating
 * soft-delete exclusions, full-text keyword match, multi-facet taxonomy filtering,
 * certification and tier flags, sorting orders, and HTTP 422 input validation bounds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.search.enabled=false"
})
class DirectoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtisanRepository artisanRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JobCategoryRepository jobCategoryRepository;

    @Autowired
    private JobSubCategoryRepository jobSubCategoryRepository;

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private TechniqueRepository techniqueRepository;

    @Autowired
    private EpoqueRepository epoqueRepository;

    @Autowired
    private EntityManager entityManager;

    private Artisan premierArtisan;
    private Artisan secondArtisan;
    private Artisan softDeletedArtisan;

    @BeforeEach
    void setUpTestData() {
        Region tiziOuzou = regionRepository.findBySlug("tizi-ouzou")
                .orElseThrow(() -> new IllegalStateException("Seeded region 'tizi-ouzou' not found"));
        Region beniYenni = regionRepository.findBySlug("beni-yenni")
                .orElseThrow(() -> new IllegalStateException("Seeded region 'beni-yenni' not found"));
        Region beniIsguen = regionRepository.findBySlug("beni-isguen")
                .orElseThrow(() -> new IllegalStateException("Seeded region 'beni-isguen' not found"));

        JobSubCategory poterieKabylie = jobSubCategoryRepository.findBySlug("macon")
                .orElseThrow(() -> new IllegalStateException("Seeded subcategory 'macon' not found"));
        JobSubCategory bijouxKabyles = jobSubCategoryRepository.findBySlug("charpentier-bois-charpentier-de-marine")
                .orElseThrow(() -> new IllegalStateException("Seeded subcategory 'charpentier-bois-charpentier-de-marine' not found"));

        Material argileRouge = materialRepository.findBySlug("pierre-calcaire")
                .orElseThrow(() -> new IllegalStateException("Seeded material 'pierre-calcaire' not found"));
        Material argentMassif = materialRepository.findBySlug("acier")
                .orElseThrow(() -> new IllegalStateException("Seeded material 'acier' not found"));

        Technique enduit = techniqueRepository.findBySlug("enduit-a-la-chaux")
                .orElseThrow(() -> new IllegalStateException("Seeded technique 'enduit-a-la-chaux' not found"));
        Technique pise = techniqueRepository.findBySlug("pise")
                .orElseThrow(() -> new IllegalStateException("Seeded technique 'pise' not found"));

        Epoque numide = epoqueRepository.findBySlug("periode-numide")
                .orElseThrow(() -> new IllegalStateException("Seeded epoque 'periode-numide' not found"));
        Epoque ottomane = epoqueRepository.findBySlug("epoque-ottomane")
                .orElseThrow(() -> new IllegalStateException("Seeded epoque 'epoque-ottomane' not found"));

        User user1 = User.builder()
                .email("ahmed.belkacem.integration@souklab.dz")
                .firstName("Ahmed")
                .lastName("Belkacem")
                .accountRole(AccountRole.ARTISAN)
                .status(AccountStatus.ACTIVE)
                .build();
        entityManager.persist(user1);

        premierArtisan = Artisan.builder()
                .user(user1)
                .bio("Maitre potier traditionnel kabyle faconnant des amphores et plats ancestraux en argile.")
                .city("Beni Yenni")
                .region(beniYenni)
                .subCategory(poterieKabylie)
                .materials(new HashSet<>(Set.of(argileRouge)))
                .techniques(new HashSet<>(Set.of(enduit)))
                .epoques(new HashSet<>(Set.of(numide)))
                .rating(4.90)
                .reviewsCount(50)
                .viewsCount(2000)
                .isVerified(true)
                .isPremium(true)
                .isTeacher(true)
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
        entityManager.persist(premierArtisan);
        user1.setArtisan(premierArtisan);

        ArtisanSubscription premiumSubscription = new ArtisanSubscription();
        premiumSubscription.setAccount(user1);
        premiumSubscription.setStatus(SubscriptionStatus.ACTIVE);
        premiumSubscription.setPlanId("directory-integration-premium");
        premiumSubscription.setPlanName("Directory integration premium");
        premiumSubscription.setBillingPeriod(BillingPeriod.MONTHLY);
        premiumSubscription.setAmount(100L);
        premiumSubscription.setCurrency("DZD");
        premiumSubscription.setEntitlementsSnapshot("{}");
        premiumSubscription.setStartsAt(LocalDateTime.now().minusMinutes(1));
        premiumSubscription.setExpiresAt(LocalDateTime.now().plusDays(30));
        entityManager.persist(premiumSubscription);

        User user2 = User.builder()
                .email("yacine.mansouri.integration@souklab.dz")
                .firstName("Yacine")
                .lastName("Mansouri")
                .accountRole(AccountRole.ARTISAN)
                .status(AccountStatus.ACTIVE)
                .build();
        entityManager.persist(user2);

        secondArtisan = Artisan.builder()
                .user(user2)
                .bio("Orfevre saharien perpetuant la tradition du filigrane et de l argent pur.")
                .city("Beni Isguen")
                .region(beniIsguen)
                .subCategory(bijouxKabyles)
                .materials(new HashSet<>(Set.of(argentMassif)))
                .techniques(new HashSet<>(Set.of(pise)))
                .epoques(new HashSet<>(Set.of(ottomane)))
                .rating(4.20)
                .reviewsCount(10)
                .viewsCount(450)
                .isVerified(false)
                .isPremium(false)
                .isTeacher(false)
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();
        entityManager.persist(secondArtisan);
        user2.setArtisan(secondArtisan);

        User user3 = User.builder()
                .email("karim.archive.integration@souklab.dz")
                .firstName("Karim")
                .lastName("Archive")
                .status(AccountStatus.ACTIVE)
                .build();
        entityManager.persist(user3);

        softDeletedArtisan = Artisan.builder()
                .user(user3)
                .bio("Artisan archive non visible.")
                .city("Tizi Ouzou")
                .region(tiziOuzou)
                .subCategory(poterieKabylie)
                .rating(5.00)
                .deletedAt(LocalDateTime.now())
                .build();
        entityManager.persist(softDeletedArtisan);
        user3.setArtisan(softDeletedArtisan);

        entityManager.flush();
    }

    /**
     * Verifies that unauthenticated callers are rejected with HTTP 403 Forbidden.
     */
    @Test
    @DisplayName("Directory: anonymous caller is rejected with HTTP 403 Forbidden")
    void defaultDirectoryBrowsing_anonymous_isRejectedWith403() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory"))
                .andExpect(status().isForbidden());
    }

    /**
     * Verifies default directory browsing returns active artisans while strictly excluding soft-deleted ones.
     */
    @Test
    @DisplayName("Directory: default browsing returns active artisans and excludes soft-deleted records")
    void defaultDirectoryBrowsing_returnsActiveArtisansAndExcludesSoftDeleted() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(premierArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(secondArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(not(hasItem(softDeletedArtisan.getId()))));
    }

    /**
     * Verifies keyword full-text matching against artisan first name, last name, and craft bio terms.
     */
    @Test
    @DisplayName("Directory: keyword search matches artisan identity and craft trade keywords")
    void keywordSearch_matchesArtisanNameAndCraftTerms() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("q", "Belkacem"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].artisanName").value(hasItem("Ahmed Belkacem")))
                .andExpect(jsonPath("$.data.content[*].artisanName").value(not(hasItem("Yacine Mansouri"))));

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("keyword", "potier"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].artisanName").value(hasItem("Ahmed Belkacem")))
                .andExpect(jsonPath("$.data.content[*].artisanName").value(not(hasItem("Yacine Mansouri"))));

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("q", "filigrane"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].artisanName").value(hasItem("Yacine Mansouri")))
                .andExpect(jsonPath("$.data.content[*].artisanName").value(not(hasItem("Ahmed Belkacem"))));
    }

    @Test
    @DisplayName("Directory: non-premium artisans see their own name while other names stay masked")
    void nonPremiumArtisanSeesOwnNameButOtherArtisansAreMasked() throws Exception {
        String maskedPremierName = "Artisan #" + premierArtisan.getId()
                .substring(premierArtisan.getId().length() - 5).toUpperCase(Locale.ROOT);

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("yacine.mansouri.integration@souklab.dz"))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].artisanName").value(hasItem("Yacine Mansouri")))
                .andExpect(jsonPath("$.data.content[*].artisanName").value(hasItem(maskedPremierName)));
    }

    /**
     * Verifies geographical terroir filtering by regional slug and Wilaya administrative code.
     */
    @Test
    @DisplayName("Directory: regional terroir filtering matches via parent Wilaya slug and Wilaya code")
    void regionalTerroirFiltering_byRegionSlugAndWilayaCode() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("regionSlug", "tizi-ouzou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(premierArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(not(hasItem(secondArtisan.getId()))));

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("wilayaCode", "47"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(secondArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(not(hasItem(premierArtisan.getId()))));
    }

    /**
     * Verifies multi-facet filtering across category, subcategory, materials, techniques, and historical epochs.
     */
    @Test
    @DisplayName("Directory: multi-facet filtering isolates artisans matching all specified taxonomy traits")
    void multiFacetFiltering_byTaxonomyHierarchies() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("categorySlug", "gros-oeuvre-structure")
                        .param("subCategorySlug", "macon")
                        .param("materials", "pierre-calcaire")
                        .param("techniques", "enduit-a-la-chaux")
                        .param("epoques", "periode-numide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(premierArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(not(hasItem(secondArtisan.getId()))));
    }

    /**
     * Verifies boolean accreditation and subscription flags filter results accurately.
     */
    @Test
    @DisplayName("Directory: flag filtering by verified, premium, and teacher status")
    void flagFiltering_verifiedPremiumTeacher() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("verifiedOnly", "true")
                        .param("premiumOnly", "true")
                        .param("teacherOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(premierArtisan.getId())))
                .andExpect(jsonPath("$.data.content[*].id").value(not(hasItem(secondArtisan.getId()))));
    }

    /**
     * Verifies sorting permutations by rating descending and chronological publication date.
     */
    @Test
    @DisplayName("Directory: sorting permutations order results by rating descending and newest first")
    void sorting_ratingDescAndNewest() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("sortBy", "RATING_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].rating").value(0.0));

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("sortBy", "NEWEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(secondArtisan.getId()));
    }

    /**
     * Verifies input validation guards reject negative page indices and excessive page sizes with HTTP 422.
     */
    @Test
    @DisplayName("Directory: validation guards reject invalid pagination parameters with HTTP 422")
    void validation_negativePageAndExcessiveSizeAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("page", "-1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/v1/public/directory")
                        .with(user("ahmed.belkacem.integration@souklab.dz"))
                        .param("size", "101"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.success").value(false));
    }
}

