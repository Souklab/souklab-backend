package com.project.souklab.controller.catalog;

import com.project.souklab.controller.support.ControllerSliceTest;
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
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.exception.UnprocessableEntityException;
import com.project.souklab.security.Permission;
import com.project.souklab.service.catalog.AdminCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = AdminCatalogController.class)
class AdminCatalogControllerTest {

    private static final String TECHNIQUES_URL = "/api/v1/admin/catalog/techniques";
    private static final String EPOQUES_URL = "/api/v1/admin/catalog/epoques";
    private static final String REGIONS_URL = "/api/v1/admin/catalog/regions";
    private static final String CATEGORIES_URL = "/api/v1/admin/catalog/categories";
    private static final String SUBCATEGORIES_URL = "/api/v1/admin/catalog/subcategories";
    private static final String MATERIAL_FAMILIES_URL = "/api/v1/admin/catalog/material-families";
    private static final String MATERIALS_URL = "/api/v1/admin/catalog/materials";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCatalogService adminCatalogService;

    // Helper for admin without ADMIN_CATALOG permission
    private static RequestPostProcessor adminWithoutCatalogPermission() {
        return user("admin-no-cat@souklab.com")
                .authorities(Permission.Admin.USERS, Permission.Admin.FEED, Permission.Admin.REPORTS);
    }

    @Nested
    @DisplayName("Security & Authorization Boundaries")
    class SecurityBoundaryTests {

        @Test
        @DisplayName("POST /techniques: 403 when unauthenticated")
        void unauthenticated_shouldReturn401() throws Exception {
            mockMvc.perform(post(TECHNIQUES_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST/PUT/PATCH/DELETE /techniques: 403 for Client role")
        void clientRole_shouldReturn403() throws Exception {
            mockMvc.perform(post(TECHNIQUES_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(put(TECHNIQUES_URL + "/tech-1").with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch(TECHNIQUES_URL + "/tech-1").with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete(TECHNIQUES_URL + "/tech-1").with(client()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST/PUT/PATCH/DELETE /techniques: 403 for Artisan role")
        void artisanRole_shouldReturn403() throws Exception {
            mockMvc.perform(post(TECHNIQUES_URL).with(artisan())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete(TECHNIQUES_URL + "/tech-1").with(artisan()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /techniques: 403 for Admin lacking ADMIN_CATALOG permission")
        void adminWithoutCatalogPermission_shouldReturn403() throws Exception {
            mockMvc.perform(post(TECHNIQUES_URL).with(adminWithoutCatalogPermission())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Endpoints for Epoque, Region, Category, SubCategory, MaterialFamily, Material: 403 for Client role")
        void clientRole_allTaxonomies_shouldReturn403() throws Exception {
            mockMvc.perform(post(EPOQUES_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(REGIONS_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(CATEGORIES_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(SUBCATEGORIES_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\",\"categoryId\":\"cat-1\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(MATERIAL_FAMILIES_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(MATERIALS_URL).with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Test\",\"familyId\":\"mf-1\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Technique Endpoints")
    class TechniqueEndpointTests {

        @Test
        @DisplayName("POST /techniques: returns 201 Created on valid input")
        void createTechnique_valid_shouldReturn201() throws Exception {
            TechniqueDTO response = TechniqueDTO.builder()
                    .id("tech-1")
                    .name("Filigrane en argent")
                    .slug("filigrane-en-argent")
                    .displayOrder(1)
                    .build();

            when(adminCatalogService.createTechnique(any(TechniqueRequest.class))).thenReturn(response);

            mockMvc.perform(post(TECHNIQUES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Filigrane en argent\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("tech-1"))
                    .andExpect(jsonPath("$.data.name").value("Filigrane en argent"))
                    .andExpect(jsonPath("$.data.slug").value("filigrane-en-argent"));
        }

        @Test
        @DisplayName("POST /techniques: returns 422 when name is missing")
        void createTechnique_missingName_shouldReturn422() throws Exception {
            mockMvc.perform(post(TECHNIQUES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"No name supplied\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("POST /techniques: returns 409 Conflict on duplicate slug")
        void createTechnique_duplicateSlug_shouldReturn409() throws Exception {
            when(adminCatalogService.createTechnique(any(TechniqueRequest.class)))
                    .thenThrow(new ConflictException("Technique with slug 'existing-slug' already exists"));

            mockMvc.perform(post(TECHNIQUES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Duplicate\",\"slug\":\"existing-slug\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /techniques/{id}: returns 200 OK on full update")
        void updateTechnique_shouldReturn200() throws Exception {
            TechniqueDTO response = TechniqueDTO.builder()
                    .id("tech-1")
                    .name("Filigrane Updated")
                    .slug("filigrane-updated")
                    .build();

            when(adminCatalogService.updateTechnique(eq("tech-1"), any(TechniqueRequest.class), eq(false)))
                    .thenReturn(response);

            mockMvc.perform(put(TECHNIQUES_URL + "/tech-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Filigrane Updated\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Filigrane Updated"));
        }

        @Test
        @DisplayName("PATCH /techniques/{id}: returns 200 OK on partial update")
        void patchTechnique_shouldReturn200() throws Exception {
            TechniqueDTO response = TechniqueDTO.builder()
                    .id("tech-1")
                    .name("Filigrane")
                    .displayOrder(99)
                    .build();

            when(adminCatalogService.updateTechnique(eq("tech-1"), any(TechniqueRequest.class), eq(true)))
                    .thenReturn(response);

            mockMvc.perform(patch(TECHNIQUES_URL + "/tech-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayOrder\":99}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.displayOrder").value(99));
        }

        @Test
        @DisplayName("DELETE /techniques/{id}: returns 200 with data:null when successfully deleted")
        void deleteTechnique_shouldReturn200WithNullData() throws Exception {
            mockMvc.perform(delete(TECHNIQUES_URL + "/tech-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(adminCatalogService).deleteTechnique("tech-1");
        }

        @Test
        @DisplayName("DELETE /techniques/{id}: returns 409 Conflict when artisan references exist")
        void deleteTechnique_withArtisans_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete: referenced by artisans"))
                    .when(adminCatalogService).deleteTechnique("tech-1");

            mockMvc.perform(delete(TECHNIQUES_URL + "/tech-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("Epoque Endpoints")
    class EpoqueEndpointTests {

        @Test
        @DisplayName("POST /epoques: returns 201 Created on valid input")
        void createEpoque_valid_shouldReturn201() throws Exception {
            EpoqueDTO response = EpoqueDTO.builder()
                    .id("ep-1")
                    .name("Période Zianide")
                    .slug("periode-zianide")
                    .periodEra("XIIIe - XVIe siècle")
                    .build();

            when(adminCatalogService.createEpoque(any(EpoqueRequest.class))).thenReturn(response);

            mockMvc.perform(post(EPOQUES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Période Zianide\",\"periodEra\":\"XIIIe - XVIe siècle\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("ep-1"))
                    .andExpect(jsonPath("$.data.periodEra").value("XIIIe - XVIe siècle"));
        }

        @Test
        @DisplayName("DELETE /epoques/{id}: returns 200 with data:null on success")
        void deleteEpoque_shouldReturn200() throws Exception {
            mockMvc.perform(delete(EPOQUES_URL + "/ep-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteEpoque("ep-1");
        }
    }

    @Nested
    @DisplayName("Region Endpoints")
    class RegionEndpointTests {

        @Test
        @DisplayName("POST /regions: returns 201 Created on valid input")
        void createRegion_valid_shouldReturn201() throws Exception {
            RegionDTO response = RegionDTO.builder()
                    .id("reg-15")
                    .name("Tizi Ouzou")
                    .slug("tizi-ouzou")
                    .code("15")
                    .build();

            when(adminCatalogService.createRegion(any(RegionRequest.class))).thenReturn(response);

            mockMvc.perform(post(REGIONS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Tizi Ouzou\",\"code\":\"15\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("reg-15"))
                    .andExpect(jsonPath("$.data.code").value("15"));
        }

        @Test
        @DisplayName("POST /regions: returns 404 when parentId is invalid")
        void createRegion_invalidParent_shouldReturn404() throws Exception {
            when(adminCatalogService.createRegion(any(RegionRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Parent region not found with id: 'missing-id'"));

            mockMvc.perform(post(REGIONS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Sub-region\",\"parentId\":\"missing-id\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /regions/{id}: returns 422 UnprocessableEntity on circular parent reference")
        void updateRegion_circularParent_shouldReturn422() throws Exception {
            when(adminCatalogService.updateRegion(eq("reg-1"), any(RegionRequest.class), eq(false)))
                    .thenThrow(new UnprocessableEntityException("Cannot set parent: circular hierarchy"));

            mockMvc.perform(put(REGIONS_URL + "/reg-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Region\",\"parentId\":\"reg-2\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("DELETE /regions/{id}: returns 409 Conflict when region has children")
        void deleteRegion_withChildren_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete: it has child regions"))
                    .when(adminCatalogService).deleteRegion("reg-1");

            mockMvc.perform(delete(REGIONS_URL + "/reg-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("DELETE /regions/{id}: returns 200 with data:null on success")
        void deleteRegion_valid_shouldReturn200() throws Exception {
            mockMvc.perform(delete(REGIONS_URL + "/reg-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteRegion("reg-1");
        }
    }

    @Nested
    @DisplayName("JobCategory Endpoints")
    class JobCategoryEndpointTests {

        @Test
        @DisplayName("POST /categories: returns 201 Created on valid input")
        void createCategory_valid_shouldReturn201() throws Exception {
            JobCategoryDTO response = JobCategoryDTO.builder()
                    .id("cat-1")
                    .name("Gros œuvre")
                    .slug("gros-oeuvre")
                    .displayOrder(1)
                    .build();

            when(adminCatalogService.createCategory(any(JobCategoryRequest.class))).thenReturn(response);

            mockMvc.perform(post(CATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Gros œuvre\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("cat-1"))
                    .andExpect(jsonPath("$.data.name").value("Gros œuvre"))
                    .andExpect(jsonPath("$.data.slug").value("gros-oeuvre"));
        }

        @Test
        @DisplayName("POST /categories: returns 422 when name is missing")
        void createCategory_missingName_shouldReturn422() throws Exception {
            mockMvc.perform(post(CATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"No name\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /categories/{id}: returns 200 OK on update")
        void updateCategory_shouldReturn200() throws Exception {
            JobCategoryDTO response = JobCategoryDTO.builder()
                    .id("cat-1")
                    .name("Gros œuvre rénové")
                    .slug("gros-oeuvre-renove")
                    .build();

            when(adminCatalogService.updateCategory(eq("cat-1"), any(JobCategoryRequest.class), eq(false)))
                    .thenReturn(response);

            mockMvc.perform(put(CATEGORIES_URL + "/cat-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Gros œuvre rénové\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.name").value("Gros œuvre rénové"));
        }

        @Test
        @DisplayName("PATCH /categories/{id}/status: returns 200 OK on status change")
        void patchCategoryStatus_shouldReturn200() throws Exception {
            JobCategoryDTO response = JobCategoryDTO.builder()
                    .id("cat-1")
                    .name("Gros œuvre")
                    .build();

            when(adminCatalogService.updateCategory(eq("cat-1"), any(JobCategoryRequest.class), eq(true)))
                    .thenReturn(response);

            mockMvc.perform(patch(CATEGORIES_URL + "/cat-1/status").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("cat-1"));
        }

        @Test
        @DisplayName("DELETE /categories/{id}: returns 200 with data:null on success")
        void deleteCategory_shouldReturn200() throws Exception {
            mockMvc.perform(delete(CATEGORIES_URL + "/cat-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteCategory("cat-1");
        }

        @Test
        @DisplayName("DELETE /categories/{id}: returns 409 Conflict when subcategories exist")
        void deleteCategory_withSubcategories_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete category with active subcategories; delete or reassign them first"))
                    .when(adminCatalogService).deleteCategory("cat-1");

            mockMvc.perform(delete(CATEGORIES_URL + "/cat-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("JobSubCategory Endpoints")
    class JobSubCategoryEndpointTests {

        @Test
        @DisplayName("POST /subcategories: returns 201 Created on valid input")
        void createSubCategory_valid_shouldReturn201() throws Exception {
            JobSubCategoryDTO response = JobSubCategoryDTO.builder()
                    .id("sub-1")
                    .name("Maçonnerie de pierre")
                    .slug("maconnerie-de-pierre")
                    .categoryId("cat-1")
                    .build();

            when(adminCatalogService.createSubCategory(any(JobSubCategoryRequest.class))).thenReturn(response);

            mockMvc.perform(post(SUBCATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Maçonnerie de pierre\",\"categoryId\":\"cat-1\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("sub-1"))
                    .andExpect(jsonPath("$.data.categoryId").value("cat-1"));
        }

        @Test
        @DisplayName("POST /subcategories: returns 422 when name is missing")
        void createSubCategory_missingName_shouldReturn422() throws Exception {
            mockMvc.perform(post(SUBCATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":\"cat-1\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("POST /subcategories: returns 400 when categoryId is missing")
        void createSubCategory_missingCategoryId_shouldReturn400() throws Exception {
            when(adminCatalogService.createSubCategory(any(JobSubCategoryRequest.class)))
                    .thenThrow(new BadRequestException("categoryId is required"));

            mockMvc.perform(post(SUBCATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Maçonnerie de pierre\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("POST /subcategories: returns 404 when parent category not found")
        void createSubCategory_parentNotFound_shouldReturn404() throws Exception {
            when(adminCatalogService.createSubCategory(any(JobSubCategoryRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Category not found with id: 'missing-cat'"));

            mockMvc.perform(post(SUBCATEGORIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Sub\",\"categoryId\":\"missing-cat\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /subcategories/{id}: returns 200 OK on update")
        void updateSubCategory_shouldReturn200() throws Exception {
            JobSubCategoryDTO response = JobSubCategoryDTO.builder()
                    .id("sub-1")
                    .name("Maçonnerie modifiée")
                    .build();

            when(adminCatalogService.updateSubCategory(eq("sub-1"), any(JobSubCategoryRequest.class), eq(false)))
                    .thenReturn(response);

            mockMvc.perform(put(SUBCATEGORIES_URL + "/sub-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Maçonnerie modifiée\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("PATCH /subcategories/{id}/status: returns 200 OK")
        void patchSubCategoryStatus_shouldReturn200() throws Exception {
            JobSubCategoryDTO response = JobSubCategoryDTO.builder()
                    .id("sub-1")
                    .name("Maçonnerie")
                    .build();

            when(adminCatalogService.updateSubCategory(eq("sub-1"), any(JobSubCategoryRequest.class), eq(true)))
                    .thenReturn(response);

            mockMvc.perform(patch(SUBCATEGORIES_URL + "/sub-1/status").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("sub-1"));
        }

        @Test
        @DisplayName("DELETE /subcategories/{id}: returns 200 with data:null on success")
        void deleteSubCategory_shouldReturn200() throws Exception {
            mockMvc.perform(delete(SUBCATEGORIES_URL + "/sub-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteSubCategory("sub-1");
        }

        @Test
        @DisplayName("DELETE /subcategories/{id}: returns 409 Conflict when referenced by artisans")
        void deleteSubCategory_withArtisans_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete: 3 artisan(s) reference it"))
                    .when(adminCatalogService).deleteSubCategory("sub-1");

            mockMvc.perform(delete(SUBCATEGORIES_URL + "/sub-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("MaterialFamily Endpoints")
    class MaterialFamilyEndpointTests {

        @Test
        @DisplayName("POST /material-families: returns 201 Created on valid input")
        void createMaterialFamily_valid_shouldReturn201() throws Exception {
            MaterialFamilyDTO response = MaterialFamilyDTO.builder()
                    .id("mf-1")
                    .name("Terres cuites")
                    .slug("terres-cuites")
                    .displayOrder(1)
                    .build();

            when(adminCatalogService.createMaterialFamily(any(MaterialFamilyRequest.class))).thenReturn(response);

            mockMvc.perform(post(MATERIAL_FAMILIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Terres cuites\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("mf-1"))
                    .andExpect(jsonPath("$.data.name").value("Terres cuites"));
        }

        @Test
        @DisplayName("POST /material-families: returns 422 when name is missing")
        void createMaterialFamily_missingName_shouldReturn422() throws Exception {
            mockMvc.perform(post(MATERIAL_FAMILIES_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"description\":\"No name\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /material-families/{id}: returns 200 OK on update")
        void updateMaterialFamily_shouldReturn200() throws Exception {
            MaterialFamilyDTO response = MaterialFamilyDTO.builder()
                    .id("mf-1")
                    .name("Terres cuites mises à jour")
                    .build();

            when(adminCatalogService.updateMaterialFamily(eq("mf-1"), any(MaterialFamilyRequest.class), eq(false)))
                    .thenReturn(response);

            mockMvc.perform(put(MATERIAL_FAMILIES_URL + "/mf-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Terres cuites mises à jour\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("PATCH /material-families/{id}/status: returns 200 OK on status change")
        void patchMaterialFamilyStatus_shouldReturn200() throws Exception {
            MaterialFamilyDTO response = MaterialFamilyDTO.builder()
                    .id("mf-1")
                    .name("Terres cuites")
                    .build();

            when(adminCatalogService.updateMaterialFamily(eq("mf-1"), any(MaterialFamilyRequest.class), eq(true)))
                    .thenReturn(response);

            mockMvc.perform(patch(MATERIAL_FAMILIES_URL + "/mf-1/status").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("mf-1"));
        }

        @Test
        @DisplayName("DELETE /material-families/{id}: returns 200 with data:null on success")
        void deleteMaterialFamily_shouldReturn200() throws Exception {
            mockMvc.perform(delete(MATERIAL_FAMILIES_URL + "/mf-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteMaterialFamily("mf-1");
        }

        @Test
        @DisplayName("DELETE /material-families/{id}: returns 409 Conflict when materials exist")
        void deleteMaterialFamily_withMaterials_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete material family with active materials; delete or reassign them first"))
                    .when(adminCatalogService).deleteMaterialFamily("mf-1");

            mockMvc.perform(delete(MATERIAL_FAMILIES_URL + "/mf-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("Material Endpoints")
    class MaterialEndpointTests {

        @Test
        @DisplayName("POST /materials: returns 201 Created on valid input")
        void createMaterial_valid_shouldReturn201() throws Exception {
            MaterialDTO response = MaterialDTO.builder()
                    .id("mat-1")
                    .name("Pierre calcaire")
                    .slug("pierre-calcaire")
                    .familyId("mf-1")
                    .build();

            when(adminCatalogService.createMaterial(any(MaterialRequest.class))).thenReturn(response);

            mockMvc.perform(post(MATERIALS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Pierre calcaire\",\"familyId\":\"mf-1\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("mat-1"))
                    .andExpect(jsonPath("$.data.familyId").value("mf-1"));
        }

        @Test
        @DisplayName("POST /materials: returns 422 when name is missing")
        void createMaterial_missingName_shouldReturn422() throws Exception {
            mockMvc.perform(post(MATERIALS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"familyId\":\"mf-1\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("POST /materials: returns 400 when familyId is missing")
        void createMaterial_missingFamilyId_shouldReturn400() throws Exception {
            when(adminCatalogService.createMaterial(any(MaterialRequest.class)))
                    .thenThrow(new BadRequestException("familyId is required"));

            mockMvc.perform(post(MATERIALS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Pierre calcaire\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("POST /materials: returns 404 when parent family not found")
        void createMaterial_familyNotFound_shouldReturn404() throws Exception {
            when(adminCatalogService.createMaterial(any(MaterialRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Material family not found with id: 'missing-fam'"));

            mockMvc.perform(post(MATERIALS_URL).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Pierre\",\"familyId\":\"missing-fam\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("PUT /materials/{id}: returns 200 OK on update")
        void updateMaterial_shouldReturn200() throws Exception {
            MaterialDTO response = MaterialDTO.builder()
                    .id("mat-1")
                    .name("Pierre modifiée")
                    .build();

            when(adminCatalogService.updateMaterial(eq("mat-1"), any(MaterialRequest.class), eq(false)))
                    .thenReturn(response);

            mockMvc.perform(put(MATERIALS_URL + "/mat-1").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Pierre modifiée\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("PATCH /materials/{id}/status: returns 200 OK")
        void patchMaterialStatus_shouldReturn200() throws Exception {
            MaterialDTO response = MaterialDTO.builder()
                    .id("mat-1")
                    .name("Pierre")
                    .build();

            when(adminCatalogService.updateMaterial(eq("mat-1"), any(MaterialRequest.class), eq(true)))
                    .thenReturn(response);

            mockMvc.perform(patch(MATERIALS_URL + "/mat-1/status").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isActive\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value("mat-1"));
        }

        @Test
        @DisplayName("DELETE /materials/{id}: returns 200 with data:null on success")
        void deleteMaterial_shouldReturn200() throws Exception {
            mockMvc.perform(delete(MATERIALS_URL + "/mat-1").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(adminCatalogService).deleteMaterial("mat-1");
        }

        @Test
        @DisplayName("DELETE /materials/{id}: returns 409 Conflict when referenced by artisans")
        void deleteMaterial_withArtisans_shouldReturn409() throws Exception {
            doThrow(new ConflictException("Cannot delete: 2 artisan(s) reference it"))
                    .when(adminCatalogService).deleteMaterial("mat-1");

            mockMvc.perform(delete(MATERIALS_URL + "/mat-1").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
