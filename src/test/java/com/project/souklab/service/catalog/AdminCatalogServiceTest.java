package com.project.souklab.service.catalog;

import com.project.souklab.dao.EpoqueRepository;
import com.project.souklab.dao.JobCategoryRepository;
import com.project.souklab.dao.JobSubCategoryRepository;
import com.project.souklab.dao.MaterialFamilyRepository;
import com.project.souklab.dao.MaterialRepository;
import com.project.souklab.dao.RegionRepository;
import com.project.souklab.dao.TechniqueRepository;
import com.project.souklab.dto.catalog.EpoqueDTO;
import com.project.souklab.dto.catalog.JobCategoryDTO;
import com.project.souklab.dto.catalog.JobSubCategoryDTO;
import com.project.souklab.dto.catalog.MaterialDTO;
import com.project.souklab.dto.catalog.MaterialFamilyDTO;
import com.project.souklab.dto.catalog.RegionDTO;
import com.project.souklab.dto.catalog.TechniqueDTO;
import com.project.souklab.dto.catalog.admin.EpoqueRequest;
import com.project.souklab.dto.catalog.admin.JobCategoryRequest;
import com.project.souklab.dto.catalog.admin.JobSubCategoryRequest;
import com.project.souklab.dto.catalog.admin.MaterialFamilyRequest;
import com.project.souklab.dto.catalog.admin.MaterialRequest;
import com.project.souklab.dto.catalog.admin.RegionRequest;
import com.project.souklab.dto.catalog.admin.TechniqueRequest;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnprocessableEntityException;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.MaterialFamily;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.service.audit.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCatalogServiceTest {

    @Mock
    private TechniqueRepository techniqueRepository;

    @Mock
    private EpoqueRepository epoqueRepository;

    @Mock
    private RegionRepository regionRepository;

    @Mock
    private JobCategoryRepository jobCategoryRepository;

    @Mock
    private JobSubCategoryRepository jobSubCategoryRepository;

    @Mock
    private MaterialFamilyRepository materialFamilyRepository;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private AdminCatalogService adminCatalogService;

    @Nested
    @DisplayName("Technique Operations")
    class TechniqueTests {

        @Test
        @DisplayName("createTechnique: auto-generates slug and default display order when omitted")
        void createTechnique_withAutoSlug_shouldSucceed() {
            TechniqueRequest req = new TechniqueRequest();
            req.setName("Filigrane d'argent");
            req.setDescription("Traditional silver filigree");

            when(techniqueRepository.existsBySlug("filigrane-d-argent")).thenReturn(false);
            when(techniqueRepository.findMaxDisplayOrder()).thenReturn(5);
            when(techniqueRepository.save(any(Technique.class))).thenAnswer(invocation -> {
                Technique t = invocation.getArgument(0);
                t.setId("tech-1");
                return t;
            });

            TechniqueDTO dto = adminCatalogService.createTechnique(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("filigrane-d-argent");
            assertThat(dto.getDisplayOrder()).isEqualTo(6);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.TECHNIQUE_CREATED), anyString());
        }

        @Test
        @DisplayName("createTechnique: throws 409 Conflict when slug already exists")
        void createTechnique_withDuplicateSlug_shouldThrowConflict() {
            TechniqueRequest req = new TechniqueRequest();
            req.setName("Filigrane d'argent");
            req.setSlug("filigrane-d-argent");

            when(techniqueRepository.existsBySlug("filigrane-d-argent")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.createTechnique(req))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("filigrane-d-argent");
            verify(techniqueRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateTechnique: throws 409 Conflict when updated slug conflicts with another technique")
        void updateTechnique_withDuplicateSlugOnOther_shouldThrowConflict() {
            Technique existing = Technique.builder().name("Original").slug("original").build();
            existing.setId("tech-1");

            when(techniqueRepository.findById("tech-1")).thenReturn(Optional.of(existing));
            when(techniqueRepository.existsBySlugAndIdNot("taken-slug", "tech-1")).thenReturn(true);

            TechniqueRequest req = new TechniqueRequest();
            req.setName("New Name");
            req.setSlug("taken-slug");

            assertThatThrownBy(() -> adminCatalogService.updateTechnique("tech-1", req, false))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("taken-slug");
        }

        @Test
        @DisplayName("updateTechnique: throws 404 when technique not found")
        void updateTechnique_notFound_shouldThrow404() {
            when(techniqueRepository.findById("tech-999")).thenReturn(Optional.empty());

            TechniqueRequest req = new TechniqueRequest();
            req.setName("Test");

            assertThatThrownBy(() -> adminCatalogService.updateTechnique("tech-999", req, false))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("deleteTechnique: throws 409 Conflict when artisan references exist")
        void deleteTechnique_withArtisanReferences_shouldThrowConflict() {
            Technique tech = Technique.builder().name("Filigrane").slug("filigrane").build();
            tech.setId("tech-1");

            when(techniqueRepository.findById("tech-1")).thenReturn(Optional.of(tech));
            when(techniqueRepository.countArtisanReferences("tech-1")).thenReturn(3);

            assertThatThrownBy(() -> adminCatalogService.deleteTechnique("tech-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("3 artisan(s) reference it");
            verify(techniqueRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteTechnique: deletes entity and creates audit log when no artisan references exist")
        void deleteTechnique_withoutReferences_shouldSucceed() {
            Technique tech = Technique.builder().name("Filigrane").slug("filigrane").build();
            tech.setId("tech-1");

            when(techniqueRepository.findById("tech-1")).thenReturn(Optional.of(tech));
            when(techniqueRepository.countArtisanReferences("tech-1")).thenReturn(0);

            adminCatalogService.deleteTechnique("tech-1");

            verify(techniqueRepository).delete(tech);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.TECHNIQUE_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("Epoque Operations")
    class EpoqueTests {

        @Test
        @DisplayName("createEpoque: auto-generates slug and persists periodEra")
        void createEpoque_shouldSucceed() {
            EpoqueRequest req = new EpoqueRequest();
            req.setName("Période Zianide");
            req.setPeriodEra("XIIIe - XVIe siècle");

            when(epoqueRepository.existsBySlug("periode-zianide")).thenReturn(false);
            when(epoqueRepository.findMaxDisplayOrder()).thenReturn(2);
            when(epoqueRepository.save(any(Epoque.class))).thenAnswer(invocation -> {
                Epoque e = invocation.getArgument(0);
                e.setId("ep-1");
                return e;
            });

            EpoqueDTO dto = adminCatalogService.createEpoque(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("periode-zianide");
            assertThat(dto.getPeriodEra()).isEqualTo("XIIIe - XVIe siècle");
            assertThat(dto.getDisplayOrder()).isEqualTo(3);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.EPOQUE_CREATED), anyString());
        }

        @Test
        @DisplayName("deleteEpoque: throws 409 Conflict when artisan references exist")
        void deleteEpoque_withArtisanReferences_shouldThrowConflict() {
            Epoque epoque = Epoque.builder().name("Zianide").slug("zianide").build();
            epoque.setId("ep-1");

            when(epoqueRepository.findById("ep-1")).thenReturn(Optional.of(epoque));
            when(epoqueRepository.countArtisanReferences("ep-1")).thenReturn(2);

            assertThatThrownBy(() -> adminCatalogService.deleteEpoque("ep-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2 artisan(s) reference it");
            verify(epoqueRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteEpoque: succeeds when no artisan references exist")
        void deleteEpoque_withoutReferences_shouldSucceed() {
            Epoque epoque = Epoque.builder().name("Zianide").slug("zianide").build();
            epoque.setId("ep-1");

            when(epoqueRepository.findById("ep-1")).thenReturn(Optional.of(epoque));
            when(epoqueRepository.countArtisanReferences("ep-1")).thenReturn(0);

            adminCatalogService.deleteEpoque("ep-1");

            verify(epoqueRepository).delete(epoque);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.EPOQUE_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("Region Operations")
    class RegionTests {

        @Test
        @DisplayName("createRegion: creates top-level Wilaya when parentId is null")
        void createRegion_topLevel_shouldSucceed() {
            RegionRequest req = new RegionRequest();
            req.setName("Tizi Ouzou");
            req.setCode("15");

            when(regionRepository.existsBySlug("tizi-ouzou")).thenReturn(false);
            when(regionRepository.findMaxDisplayOrder()).thenReturn(14);
            when(regionRepository.save(any(Region.class))).thenAnswer(invocation -> {
                Region r = invocation.getArgument(0);
                r.setId("reg-15");
                return r;
            });

            RegionDTO dto = adminCatalogService.createRegion(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("tizi-ouzou");
            assertThat(dto.getCode()).isEqualTo("15");
            assertThat(dto.getDisplayOrder()).isEqualTo(15);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.REGION_CREATED), anyString());
        }

        @Test
        @DisplayName("createRegion: throws 404 when parentId does not exist")
        void createRegion_withNonExistentParent_shouldThrow404() {
            RegionRequest req = new RegionRequest();
            req.setName("Beni Yenni");
            req.setParentId("unknown-parent");

            when(regionRepository.existsBySlug("beni-yenni")).thenReturn(false);
            when(regionRepository.findById("unknown-parent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminCatalogService.createRegion(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Parent region not found");
        }

        @Test
        @DisplayName("updateRegion: throws 422 UnprocessableEntity when setting parent creates a cycle")
        void updateRegion_circularParent_shouldThrow422() {
            Region wilaya = Region.builder().name("Wilaya").slug("wilaya").build();
            wilaya.setId("reg-1");

            when(regionRepository.findById("reg-1")).thenReturn(Optional.of(wilaya));
            when(regionRepository.existsBySlugAndIdNot("wilaya", "reg-1")).thenReturn(false);

            Region commune = Region.builder().name("Commune").slug("commune").build();
            commune.setId("reg-2");
            when(regionRepository.findById("reg-2")).thenReturn(Optional.of(commune));

            // reg-1 is already an ancestor of reg-2
            when(regionRepository.findAncestorIds("reg-2")).thenReturn(List.of("reg-1"));

            RegionRequest req = new RegionRequest();
            req.setName("Wilaya");
            req.setParentId("reg-2"); // Attempting to make reg-1's parent reg-2!

            assertThatThrownBy(() -> adminCatalogService.updateRegion("reg-1", req, false))
                    .isInstanceOf(UnprocessableEntityException.class)
                    .hasMessageContaining("circular hierarchy");
        }

        @Test
        @DisplayName("updateRegion: throws 422 UnprocessableEntity when setting region as its own parent")
        void updateRegion_selfAsParent_shouldThrow422() {
            Region wilaya = Region.builder().name("Wilaya").slug("wilaya").build();
            wilaya.setId("reg-1");

            when(regionRepository.findById("reg-1")).thenReturn(Optional.of(wilaya));
            when(regionRepository.existsBySlugAndIdNot("wilaya", "reg-1")).thenReturn(false);

            RegionRequest req = new RegionRequest();
            req.setName("Wilaya");
            req.setParentId("reg-1");

            assertThatThrownBy(() -> adminCatalogService.updateRegion("reg-1", req, false))
                    .isInstanceOf(UnprocessableEntityException.class)
                    .hasMessageContaining("circular hierarchy");
        }

        @Test
        @DisplayName("deleteRegion: throws 409 Conflict when child regions exist")
        void deleteRegion_withChildRegions_shouldThrowConflict() {
            Region wilaya = Region.builder().name("Tizi Ouzou").slug("tizi-ouzou").build();
            wilaya.setId("reg-15");

            when(regionRepository.findById("reg-15")).thenReturn(Optional.of(wilaya));
            when(regionRepository.existsByParentId("reg-15")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.deleteRegion("reg-15"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("it has child regions");
            verify(regionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteRegion: deletes region and logs audit when no children exist")
        void deleteRegion_withoutChildren_shouldSucceed() {
            Region commune = Region.builder().name("Beni Yenni").slug("beni-yenni").build();
            commune.setId("reg-com-1");

            when(regionRepository.findById("reg-com-1")).thenReturn(Optional.of(commune));
            when(regionRepository.existsByParentId("reg-com-1")).thenReturn(false);

            adminCatalogService.deleteRegion("reg-com-1");

            verify(regionRepository).delete(commune);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.REGION_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("JobCategory Operations")
    class JobCategoryTests {

        @Test
        @DisplayName("createCategory: auto-generates slug and default display order when omitted")
        void createCategory_withAutoSlug_shouldSucceed() {
            JobCategoryRequest req = new JobCategoryRequest();
            req.setName("Gros Œuvre & Maçonnerie");
            req.setDescription("Travaux de gros oeuvre");

            when(jobCategoryRepository.existsBySlug("gros-oeuvre-maconnerie")).thenReturn(false);
            when(jobCategoryRepository.findMaxDisplayOrder()).thenReturn(3);
            when(jobCategoryRepository.save(any(JobCategory.class))).thenAnswer(invocation -> {
                JobCategory c = invocation.getArgument(0);
                c.setId("cat-1");
                return c;
            });

            JobCategoryDTO dto = adminCatalogService.createCategory(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("gros-oeuvre-maconnerie");
            assertThat(dto.getDisplayOrder()).isEqualTo(4);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.CATEGORY_CREATED), anyString());
        }

        @Test
        @DisplayName("createCategory: throws 422 UnprocessableEntity when explicit slug format is invalid")
        void createCategory_withInvalidSlugFormat_shouldThrow422() {
            JobCategoryRequest req = new JobCategoryRequest();
            req.setName("Category");
            req.setSlug("Invalid Slug!");

            assertThatThrownBy(() -> adminCatalogService.createCategory(req))
                    .isInstanceOf(UnprocessableEntityException.class)
                    .hasMessageContaining("Invalid slug format");
            verify(jobCategoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("createCategory: throws 409 Conflict when slug already exists")
        void createCategory_withDuplicateSlug_shouldThrowConflict() {
            JobCategoryRequest req = new JobCategoryRequest();
            req.setName("Peinture");
            req.setSlug("peinture");

            when(jobCategoryRepository.existsBySlug("peinture")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.createCategory(req))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("peinture");
            verify(jobCategoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateCategory: throws 409 Conflict when slug conflicts with another category")
        void updateCategory_withDuplicateSlugOnOther_shouldThrowConflict() {
            JobCategory existing = JobCategory.builder().name("Peinture").slug("peinture").build();
            existing.setId("cat-1");

            when(jobCategoryRepository.findById("cat-1")).thenReturn(Optional.of(existing));
            when(jobCategoryRepository.existsBySlugAndIdNot("taken-slug", "cat-1")).thenReturn(true);

            JobCategoryRequest req = new JobCategoryRequest();
            req.setName("New Name");
            req.setSlug("taken-slug");

            assertThatThrownBy(() -> adminCatalogService.updateCategory("cat-1", req, false))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("taken-slug");
        }

        @Test
        @DisplayName("updateCategory: throws 404 when category not found")
        void updateCategory_notFound_shouldThrow404() {
            when(jobCategoryRepository.findById("cat-999")).thenReturn(Optional.empty());

            JobCategoryRequest req = new JobCategoryRequest();
            req.setName("Test");

            assertThatThrownBy(() -> adminCatalogService.updateCategory("cat-999", req, false))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("deleteCategory: throws 409 Conflict when subcategories exist")
        void deleteCategory_withSubcategories_shouldThrowConflict() {
            JobCategory cat = JobCategory.builder().name("Plomberie").slug("plomberie").build();
            cat.setId("cat-1");

            when(jobCategoryRepository.findById("cat-1")).thenReturn(Optional.of(cat));
            when(jobSubCategoryRepository.existsByCategoryId("cat-1")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.deleteCategory("cat-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot delete category with active subcategories; delete or reassign them first");
            verify(jobCategoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteCategory: deletes entity and creates audit log when no subcategories exist")
        void deleteCategory_withoutSubcategories_shouldSucceed() {
            JobCategory cat = JobCategory.builder().name("Plomberie").slug("plomberie").build();
            cat.setId("cat-1");

            when(jobCategoryRepository.findById("cat-1")).thenReturn(Optional.of(cat));
            when(jobSubCategoryRepository.existsByCategoryId("cat-1")).thenReturn(false);

            adminCatalogService.deleteCategory("cat-1");

            verify(jobCategoryRepository).delete(cat);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.CATEGORY_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("JobSubCategory Operations")
    class JobSubCategoryTests {

        @Test
        @DisplayName("createSubCategory: assigns parent, calculates max display order in category, and creates entity")
        void createSubCategory_shouldSucceed() {
            JobCategory parent = JobCategory.builder().name("Gros Œuvre").slug("gros-oeuvre").build();
            parent.setId("cat-1");

            JobSubCategoryRequest req = new JobSubCategoryRequest();
            req.setName("Taille de pierre");
            req.setCategoryId("cat-1");

            when(jobCategoryRepository.findById("cat-1")).thenReturn(Optional.of(parent));
            when(jobSubCategoryRepository.existsBySlug("taille-de-pierre")).thenReturn(false);
            when(jobSubCategoryRepository.findMaxDisplayOrderByCategoryId("cat-1")).thenReturn(2);
            when(jobSubCategoryRepository.save(any(JobSubCategory.class))).thenAnswer(invocation -> {
                JobSubCategory sub = invocation.getArgument(0);
                sub.setId("sub-1");
                return sub;
            });

            JobSubCategoryDTO dto = adminCatalogService.createSubCategory(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("taille-de-pierre");
            assertThat(dto.getCategoryId()).isEqualTo("cat-1");
            assertThat(dto.getDisplayOrder()).isEqualTo(3);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.SUBCATEGORY_CREATED), anyString());
        }

        @Test
        @DisplayName("createSubCategory: throws 404 ResourceNotFound when parent category does not exist")
        void createSubCategory_parentNotFound_shouldThrow404() {
            JobSubCategoryRequest req = new JobSubCategoryRequest();
            req.setName("Test Sub");
            req.setCategoryId("nonexistent-cat");

            when(jobCategoryRepository.findById("nonexistent-cat")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminCatalogService.createSubCategory(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Category not found with id: 'nonexistent-cat'");
        }

        @Test
        @DisplayName("createSubCategory: throws 409 Conflict when slug already exists globally")
        void createSubCategory_duplicateSlug_shouldThrowConflict() {
            JobCategory parent = JobCategory.builder().name("Gros Œuvre").build();
            parent.setId("cat-1");

            JobSubCategoryRequest req = new JobSubCategoryRequest();
            req.setName("Maçonnerie");
            req.setSlug("maconnerie");
            req.setCategoryId("cat-1");

            when(jobCategoryRepository.findById("cat-1")).thenReturn(Optional.of(parent));
            when(jobSubCategoryRepository.existsBySlug("maconnerie")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.createSubCategory(req))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("maconnerie");
        }

        @Test
        @DisplayName("deleteSubCategory: throws 409 Conflict when artisan references exist")
        void deleteSubCategory_withArtisanReferences_shouldThrowConflict() {
            JobSubCategory sub = JobSubCategory.builder().name("Maçonnerie").slug("maconnerie").build();
            sub.setId("sub-1");

            when(jobSubCategoryRepository.findById("sub-1")).thenReturn(Optional.of(sub));
            when(jobSubCategoryRepository.countArtisanReferences("sub-1")).thenReturn(4);

            assertThatThrownBy(() -> adminCatalogService.deleteSubCategory("sub-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("4 artisan(s) reference it");
            verify(jobSubCategoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteSubCategory: deletes entity and creates audit log when no artisan references exist")
        void deleteSubCategory_withoutReferences_shouldSucceed() {
            JobSubCategory sub = JobSubCategory.builder().name("Maçonnerie").slug("maconnerie").build();
            sub.setId("sub-1");

            when(jobSubCategoryRepository.findById("sub-1")).thenReturn(Optional.of(sub));
            when(jobSubCategoryRepository.countArtisanReferences("sub-1")).thenReturn(0);

            adminCatalogService.deleteSubCategory("sub-1");

            verify(jobSubCategoryRepository).delete(sub);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.SUBCATEGORY_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("MaterialFamily Operations")
    class MaterialFamilyTests {

        @Test
        @DisplayName("createMaterialFamily: auto-generates slug and default display order")
        void createMaterialFamily_shouldSucceed() {
            MaterialFamilyRequest req = new MaterialFamilyRequest();
            req.setName("Terres cuites & Céramiques");

            when(materialFamilyRepository.existsBySlug("terres-cuites-ceramiques")).thenReturn(false);
            when(materialFamilyRepository.findMaxDisplayOrder()).thenReturn(1);
            when(materialFamilyRepository.save(any(MaterialFamily.class))).thenAnswer(invocation -> {
                MaterialFamily mf = invocation.getArgument(0);
                mf.setId("mf-1");
                return mf;
            });

            MaterialFamilyDTO dto = adminCatalogService.createMaterialFamily(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("terres-cuites-ceramiques");
            assertThat(dto.getDisplayOrder()).isEqualTo(2);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.MATERIAL_FAMILY_CREATED), anyString());
        }

        @Test
        @DisplayName("deleteMaterialFamily: throws 409 Conflict when materials exist")
        void deleteMaterialFamily_withMaterials_shouldThrowConflict() {
            MaterialFamily mf = MaterialFamily.builder().name("Pierres").slug("pierres").build();
            mf.setId("mf-1");

            when(materialFamilyRepository.findById("mf-1")).thenReturn(Optional.of(mf));
            when(materialRepository.existsByFamilyId("mf-1")).thenReturn(true);

            assertThatThrownBy(() -> adminCatalogService.deleteMaterialFamily("mf-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("Cannot delete material family with active materials; delete or reassign them first");
            verify(materialFamilyRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteMaterialFamily: deletes entity and creates audit log when no materials exist")
        void deleteMaterialFamily_withoutMaterials_shouldSucceed() {
            MaterialFamily mf = MaterialFamily.builder().name("Pierres").slug("pierres").build();
            mf.setId("mf-1");

            when(materialFamilyRepository.findById("mf-1")).thenReturn(Optional.of(mf));
            when(materialRepository.existsByFamilyId("mf-1")).thenReturn(false);

            adminCatalogService.deleteMaterialFamily("mf-1");

            verify(materialFamilyRepository).delete(mf);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.MATERIAL_FAMILY_DELETED), anyString());
        }
    }

    @Nested
    @DisplayName("Material Operations")
    class MaterialTests {

        @Test
        @DisplayName("createMaterial: assigns family, calculates max display order in family, and creates entity")
        void createMaterial_shouldSucceed() {
            MaterialFamily family = MaterialFamily.builder().name("Pierres").slug("pierres").build();
            family.setId("mf-1");

            MaterialRequest req = new MaterialRequest();
            req.setName("Pierre calcaire de taille");
            req.setFamilyId("mf-1");

            when(materialFamilyRepository.findById("mf-1")).thenReturn(Optional.of(family));
            when(materialRepository.existsBySlug("pierre-calcaire-de-taille")).thenReturn(false);
            when(materialRepository.findMaxDisplayOrderByFamilyId("mf-1")).thenReturn(4);
            when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> {
                Material m = invocation.getArgument(0);
                m.setId("mat-1");
                return m;
            });

            MaterialDTO dto = adminCatalogService.createMaterial(req);

            assertThat(dto).isNotNull();
            assertThat(dto.getSlug()).isEqualTo("pierre-calcaire-de-taille");
            assertThat(dto.getFamilyId()).isEqualTo("mf-1");
            assertThat(dto.getDisplayOrder()).isEqualTo(5);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.MATERIAL_CREATED), anyString());
        }

        @Test
        @DisplayName("createMaterial: throws 404 ResourceNotFound when parent family does not exist")
        void createMaterial_familyNotFound_shouldThrow404() {
            MaterialRequest req = new MaterialRequest();
            req.setName("Zellige");
            req.setFamilyId("nonexistent-family");

            when(materialFamilyRepository.findById("nonexistent-family")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminCatalogService.createMaterial(req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Material family not found with id: 'nonexistent-family'");
        }

        @Test
        @DisplayName("deleteMaterial: throws 409 Conflict when artisan references exist")
        void deleteMaterial_withArtisanReferences_shouldThrowConflict() {
            Material mat = Material.builder().name("Chaux hydraulique").slug("chaux-hydraulique").build();
            mat.setId("mat-1");

            when(materialRepository.findById("mat-1")).thenReturn(Optional.of(mat));
            when(materialRepository.countArtisanReferences("mat-1")).thenReturn(2);

            assertThatThrownBy(() -> adminCatalogService.deleteMaterial("mat-1"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("2 artisan(s) reference it");
            verify(materialRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteMaterial: deletes entity and creates audit log when no artisan references exist")
        void deleteMaterial_withoutReferences_shouldSucceed() {
            Material mat = Material.builder().name("Chaux hydraulique").slug("chaux-hydraulique").build();
            mat.setId("mat-1");

            when(materialRepository.findById("mat-1")).thenReturn(Optional.of(mat));
            when(materialRepository.countArtisanReferences("mat-1")).thenReturn(0);

            adminCatalogService.deleteMaterial("mat-1");

            verify(materialRepository).delete(mat);
            verify(auditLogService).logAction(eq(AuditLogAction.Catalog.MATERIAL_DELETED), anyString());
        }
    }
}
