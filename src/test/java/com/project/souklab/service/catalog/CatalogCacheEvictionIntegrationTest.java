package com.project.souklab.service.catalog;

import com.project.souklab.config.CacheProperties;
import com.project.souklab.config.CacheConfig;
import com.project.souklab.dao.EpoqueRepository;
import com.project.souklab.dao.JobCategoryRepository;
import com.project.souklab.dao.JobSubCategoryRepository;
import com.project.souklab.dao.MaterialFamilyRepository;
import com.project.souklab.dao.MaterialRepository;
import com.project.souklab.dao.RegionRepository;
import com.project.souklab.dao.TechniqueRepository;
import com.project.souklab.dto.catalog.EpoqueDTO;
import com.project.souklab.dto.catalog.JobCategoryDTO;
import com.project.souklab.dto.catalog.MaterialFamilyDTO;
import com.project.souklab.dto.catalog.RegionDTO;
import com.project.souklab.dto.catalog.TechniqueDTO;
import com.project.souklab.dto.catalog.admin.EpoqueRequest;
import com.project.souklab.dto.catalog.admin.JobCategoryRequest;
import com.project.souklab.dto.catalog.admin.MaterialFamilyRequest;
import com.project.souklab.dto.catalog.admin.RegionRequest;
import com.project.souklab.dto.catalog.admin.TechniqueRequest;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.MaterialFamily;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.service.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test verifying that Spring's Caffeine cache abstraction correctly caches
 * public catalog reads and that write operations in {@link AdminCatalogService} evict
 * those cached entries in real-time.
 */
@SpringJUnitConfig
@Import({CacheConfig.class, CatalogService.class, AdminCatalogService.class})
@EnableConfigurationProperties(CacheProperties.class)
@TestPropertySource(properties = {
        "app.cache.expire-after-write=10m",
        "app.cache.maximum-size=500"
})
class CatalogCacheEvictionIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private AdminCatalogService adminCatalogService;

    @MockitoBean
    private TechniqueRepository techniqueRepository;

    @MockitoBean
    private EpoqueRepository epoqueRepository;

    @MockitoBean
    private RegionRepository regionRepository;

    @MockitoBean
    private JobCategoryRepository jobCategoryRepository;

    @MockitoBean
    private JobSubCategoryRepository jobSubCategoryRepository;

    @MockitoBean
    private MaterialFamilyRepository materialFamilyRepository;

    @MockitoBean
    private MaterialRepository materialRepository;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    @DisplayName("Technique cache: GET caches list, admin POST evicts cache, subsequent GET reflects new item")
    void techniqueCache_getPostGet_shouldInvalidateAndReflectNewItem() {
        Technique t1 = Technique.builder().name("Filigrane").slug("filigrane").displayOrder(1).build();
        t1.setId("tech-1");

        Technique t2 = Technique.builder().name("Ciselure").slug("ciselure").displayOrder(2).build();
        t2.setId("tech-2");

        // 1. Initial state: repository returns [t1]
        when(techniqueRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(t1));

        List<TechniqueDTO> firstGet = catalogService.getAllTechniques();
        assertThat(firstGet).hasSize(1);
        assertThat(firstGet.get(0).getName()).isEqualTo("Filigrane");
        verify(techniqueRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // 2. Second GET without write: should be served from cache (no additional repo invocation)
        List<TechniqueDTO> secondGet = catalogService.getAllTechniques();
        assertThat(secondGet).hasSize(1);
        verify(techniqueRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // 3. Admin writes a new technique: repo now returns [t1, t2]
        when(techniqueRepository.existsBySlug("ciselure")).thenReturn(false);
        when(techniqueRepository.findMaxDisplayOrder()).thenReturn(1);
        when(techniqueRepository.save(any(Technique.class))).thenReturn(t2);
        when(techniqueRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(t1, t2));

        TechniqueRequest req = new TechniqueRequest();
        req.setName("Ciselure");
        adminCatalogService.createTechnique(req);

        // 4. Third GET: cache should be evicted, repository called a 2nd time, new item reflected
        List<TechniqueDTO> thirdGet = catalogService.getAllTechniques();
        assertThat(thirdGet).hasSize(2);
        assertThat(thirdGet.get(1).getName()).isEqualTo("Ciselure");
        verify(techniqueRepository, times(2)).findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    @Test
    @DisplayName("Epoque cache: GET caches list, admin POST evicts cache, subsequent GET reflects new item")
    void epoqueCache_getPostGet_shouldInvalidateAndReflectNewItem() {
        Epoque e1 = Epoque.builder().name("Numide").slug("numide").displayOrder(1).build();
        e1.setId("ep-1");

        Epoque e2 = Epoque.builder().name("Zianide").slug("zianide").displayOrder(2).build();
        e2.setId("ep-2");

        when(epoqueRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(e1));

        List<EpoqueDTO> firstGet = catalogService.getAllEpoques();
        assertThat(firstGet).hasSize(1);
        verify(epoqueRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Cached
        catalogService.getAllEpoques();
        verify(epoqueRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Admin write
        when(epoqueRepository.existsBySlug("zianide")).thenReturn(false);
        when(epoqueRepository.findMaxDisplayOrder()).thenReturn(1);
        when(epoqueRepository.save(any(Epoque.class))).thenReturn(e2);
        when(epoqueRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(e1, e2));

        EpoqueRequest req = new EpoqueRequest();
        req.setName("Zianide");
        adminCatalogService.createEpoque(req);

        // Evicted -> fetches fresh
        List<EpoqueDTO> afterPost = catalogService.getAllEpoques();
        assertThat(afterPost).hasSize(2);
        verify(epoqueRepository, times(2)).findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    @Test
    @DisplayName("Region cache: GET caches list, admin POST evicts cache, subsequent GET reflects new item")
    void regionCache_getPostGet_shouldInvalidateAndReflectNewItem() {
        Region r1 = Region.builder().name("Alger").slug("alger").displayOrder(1).build();
        r1.setId("reg-16");

        Region r2 = Region.builder().name("Oran").slug("oran").displayOrder(2).build();
        r2.setId("reg-31");

        when(regionRepository.findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(r1));
        when(regionRepository.findByParentIdAndIsActiveTrueOrderByDisplayOrderAsc("reg-16")).thenReturn(List.of());

        List<RegionDTO> firstGet = catalogService.getAllRegions();
        assertThat(firstGet).hasSize(1);
        verify(regionRepository, times(1)).findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc();

        // Cached
        catalogService.getAllRegions();
        verify(regionRepository, times(1)).findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc();

        // Admin write
        when(regionRepository.existsBySlug("oran")).thenReturn(false);
        when(regionRepository.findMaxDisplayOrder()).thenReturn(1);
        when(regionRepository.save(any(Region.class))).thenReturn(r2);
        when(regionRepository.findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(r1, r2));
        when(regionRepository.findByParentIdAndIsActiveTrueOrderByDisplayOrderAsc("reg-31")).thenReturn(List.of());

        RegionRequest req = new RegionRequest();
        req.setName("Oran");
        adminCatalogService.createRegion(req);

        // Evicted -> fetches fresh
        List<RegionDTO> afterPost = catalogService.getAllRegions();
        assertThat(afterPost).hasSize(2);
        verify(regionRepository, times(2)).findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc();
    }

    @Test
    @DisplayName("Category cache: GET caches list, admin POST evicts cache, subsequent GET reflects new item")
    void categoryCache_getPostGet_shouldInvalidateAndReflectNewItem() {
        JobCategory c1 = JobCategory.builder().name("Gros oeuvre").slug("gros-oeuvre").displayOrder(1).build();
        c1.setId("cat-1");

        JobCategory c2 = JobCategory.builder().name("Peinture").slug("peinture").displayOrder(2).build();
        c2.setId("cat-2");

        when(jobCategoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(c1));
        when(jobSubCategoryRepository.findByCategoryIdAndIsActiveTrueOrderByDisplayOrderAsc("cat-1")).thenReturn(List.of());

        List<JobCategoryDTO> firstGet = catalogService.getAllCategories();
        assertThat(firstGet).hasSize(1);
        verify(jobCategoryRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Cached
        catalogService.getAllCategories();
        verify(jobCategoryRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Admin write
        when(jobCategoryRepository.existsBySlug("peinture")).thenReturn(false);
        when(jobCategoryRepository.findMaxDisplayOrder()).thenReturn(1);
        when(jobCategoryRepository.save(any(JobCategory.class))).thenReturn(c2);
        when(jobCategoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(c1, c2));
        when(jobSubCategoryRepository.findByCategoryIdAndIsActiveTrueOrderByDisplayOrderAsc("cat-2")).thenReturn(List.of());

        JobCategoryRequest req = new JobCategoryRequest();
        req.setName("Peinture");
        adminCatalogService.createCategory(req);

        // Evicted -> fetches fresh
        List<JobCategoryDTO> afterPost = catalogService.getAllCategories();
        assertThat(afterPost).hasSize(2);
        verify(jobCategoryRepository, times(2)).findByIsActiveTrueOrderByDisplayOrderAsc();
    }

    @Test
    @DisplayName("MaterialFamily cache: GET caches list, admin POST evicts cache, subsequent GET reflects new item")
    void materialFamilyCache_getPostGet_shouldInvalidateAndReflectNewItem() {
        MaterialFamily mf1 = MaterialFamily.builder().name("Terres").slug("terres").displayOrder(1).build();
        mf1.setId("mf-1");

        MaterialFamily mf2 = MaterialFamily.builder().name("Pierres").slug("pierres").displayOrder(2).build();
        mf2.setId("mf-2");

        when(materialFamilyRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(mf1));
        when(materialRepository.findByFamilyIdAndIsActiveTrueOrderByDisplayOrderAsc("mf-1")).thenReturn(List.of());

        List<MaterialFamilyDTO> firstGet = catalogService.getAllMaterials();
        assertThat(firstGet).hasSize(1);
        verify(materialFamilyRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Cached
        catalogService.getAllMaterials();
        verify(materialFamilyRepository, times(1)).findByIsActiveTrueOrderByDisplayOrderAsc();

        // Admin write
        when(materialFamilyRepository.existsBySlug("pierres")).thenReturn(false);
        when(materialFamilyRepository.findMaxDisplayOrder()).thenReturn(1);
        when(materialFamilyRepository.save(any(MaterialFamily.class))).thenReturn(mf2);
        when(materialFamilyRepository.findByIsActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(mf1, mf2));
        when(materialRepository.findByFamilyIdAndIsActiveTrueOrderByDisplayOrderAsc("mf-2")).thenReturn(List.of());

        MaterialFamilyRequest req = new MaterialFamilyRequest();
        req.setName("Pierres");
        adminCatalogService.createMaterialFamily(req);

        // Evicted -> fetches fresh
        List<MaterialFamilyDTO> afterPost = catalogService.getAllMaterials();
        assertThat(afterPost).hasSize(2);
        verify(materialFamilyRepository, times(2)).findByIsActiveTrueOrderByDisplayOrderAsc();
    }
}
