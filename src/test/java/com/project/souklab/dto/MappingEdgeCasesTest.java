package com.project.souklab.dto;

import com.project.souklab.dto.auth.CompleteProfileRequestDTO;
import com.project.souklab.dto.auth.LoginDTO;
import com.project.souklab.dto.artisan.CertificationResponseDTO;
import com.project.souklab.dto.artisan.GalleryImageResponseDTO;
import com.project.souklab.dto.catalog.EpoqueDTO;
import com.project.souklab.dto.catalog.EpoqueSummaryDTO;
import com.project.souklab.dto.catalog.JobCategoryDTO;
import com.project.souklab.dto.catalog.JobSubCategoryDTO;
import com.project.souklab.dto.catalog.MaterialDTO;
import com.project.souklab.dto.catalog.MaterialFamilyDTO;
import com.project.souklab.dto.catalog.RegionDTO;
import com.project.souklab.dto.catalog.TechniqueDTO;
import com.project.souklab.dto.catalog.TechniqueSummaryDTO;
import com.project.souklab.model.Epoque;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.MaterialFamily;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MappingEdgeCasesTest {

    @Test
    void catalogMappersHandleNullEntitiesAndOptionalRelationships() {
        assertThat(MaterialFamilyDTO.from(null, null)).isNull();
        assertThat(RegionDTO.from(null, null)).isNull();
        assertThat(MaterialDTO.from(null)).isNull();
        assertThat(TechniqueDTO.from(null)).isNull();
        assertThat(JobSubCategoryDTO.from(null)).isNull();
        assertThat(JobCategoryDTO.from(null, null)).isNull();
        assertThat(EpoqueDTO.from(null)).isNull();
        assertThat(EpoqueSummaryDTO.from(null)).isNull();
        assertThat(TechniqueSummaryDTO.from(null)).isNull();

        MaterialFamily family = new MaterialFamily();
        family.setId("family");
        family.setName("Family");
        Material material = new Material();
        material.setId("material");
        material.setFamily(null);
        assertThat(MaterialDTO.from(material).getFamilyId()).isNull();
        assertThat(MaterialFamilyDTO.from(family, null).getMaterials()).isEmpty();

        JobCategory category = new JobCategory();
        category.setId("category");
        JobSubCategory subCategory = new JobSubCategory();
        subCategory.setId("sub-category");
        subCategory.setCategory(null);
        assertThat(JobSubCategoryDTO.from(subCategory).getCategoryId()).isNull();
        assertThat(JobCategoryDTO.from(category, null).getSubCategories()).isEmpty();

        Region region = new Region();
        region.setId("region");
        assertThat(RegionDTO.from(region, null).getChildren()).isEmpty();
        assertThat(RegionDTO.from(region).getChildren()).isEmpty();
    }

    @Test
    void catalogMappersCopyEntityValues() {
        Epoque epoque = new Epoque();
        epoque.setId("epoque");
        epoque.setName("Era");
        epoque.setSlug("era");
        epoque.setPeriodEra("1900");
        epoque.setDescription("desc");
        epoque.setDisplayOrder(2);
        assertThat(EpoqueDTO.from(epoque).getId()).isEqualTo("epoque");
        assertThat(EpoqueSummaryDTO.from(epoque).getPeriodEra()).isEqualTo("1900");

        Technique technique = new Technique();
        technique.setId("technique");
        technique.setName("Technique");
        technique.setSlug("technique");
        assertThat(TechniqueDTO.from(technique).getName()).isEqualTo("Technique");
        assertThat(TechniqueSummaryDTO.from(technique).getSlug()).isEqualTo("technique");
    }

    @Test
    void simpleAuthAndMediaMappersExposeFallbacks() {
        LoginDTO login = LoginDTO.builder().email("  ").username(" user ").password("secret").build();
        assertThat(login.getLoginIdentifier()).isEqualTo("user");
        assertThat(LoginDTO.builder().password("secret").build().getLoginIdentifier()).isNull();

        CompleteProfileRequestDTO profile = CompleteProfileRequestDTO.builder().region("legacy").build();
        assertThat(profile.resolveRegionId()).isEqualTo("legacy");
        profile.setRegionId(" current ");
        assertThat(profile.resolveRegionId()).isEqualTo(" current ");

        assertThat(CertificationResponseDTO.from(null)).isNull();
        assertThat(GalleryImageResponseDTO.from(null)).isNull();
        assertThat(List.of()).isEmpty();
    }
}
