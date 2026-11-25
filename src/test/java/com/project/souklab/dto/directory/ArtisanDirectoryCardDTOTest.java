package com.project.souklab.dto.directory;

import java.util.LinkedHashSet;

import com.project.souklab.model.Artisan;
import com.project.souklab.model.ArtisanGalleryImage;
import com.project.souklab.model.JobCategory;
import com.project.souklab.model.JobSubCategory;
import com.project.souklab.model.Material;
import com.project.souklab.model.Region;
import com.project.souklab.model.Technique;
import com.project.souklab.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests verifying {@link ArtisanDirectoryCardDTO} mapping, bio truncation,
 * Wilaya resolution, and gallery cover image extraction.
 */
class ArtisanDirectoryCardDTOTest {

    @Test
    @DisplayName("from: when artisan is null should return null (single-arg and two-arg overloads)")
    void from_whenArtisanIsNull_shouldReturnNull() {
        assertThat(ArtisanDirectoryCardDTO.from(null)).isNull();
        assertThat(ArtisanDirectoryCardDTO.from(null, false)).isNull();
        assertThat(ArtisanDirectoryCardDTO.from(null, true)).isNull();
    }

    @Test
    @DisplayName("from(artisan, false): unlocked shows real artisan name")
    void from_withContactInfoUnlocked_showsRealName() {
        User user = User.builder().firstName("Djamel").lastName("Amrani").build();
        Artisan artisan = Artisan.builder()
                .id("abc123def456")
                .user(user)
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, false);

        assertThat(dto.getArtisanName()).isEqualTo("Djamel Amrani");
    }

    @Test
    @DisplayName("from(artisan, true): locked replaces name with Artisan #XXXXX (last 5 chars of ID, uppercase)")
    void from_withContactInfoLocked_replacesNameWithAnonymisedId() {
        User user = User.builder().firstName("Djamel").lastName("Amrani").build();
        Artisan artisan = Artisan.builder()
                .id("abc123def456")
                .user(user)
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, true);

        assertThat(dto.getArtisanName()).isEqualTo("Artisan #EF456");
    }

    @Test
    @DisplayName("from(artisan, true): locked with short ID (< 5 chars) uses the full ID")
    void from_withContactInfoLocked_shortId_usesFullId() {
        Artisan artisan = Artisan.builder().id("ab1").build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, true);

        assertThat(dto.getArtisanName()).isEqualTo("Artisan #AB1");
    }

    @Test
    @DisplayName("from(artisan, true): locked with null ID produces fallback placeholder")
    void from_withContactInfoLocked_nullId_producesFallback() {
        Artisan artisan = Artisan.builder().id(null).build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, true);

        assertThat(dto.getArtisanName()).isEqualTo("Artisan #?????");
    }

    @Test
    @DisplayName("from(artisan, true): non-sensitive fields (avatarUrl, city, rating) are still exposed when locked")
    void from_withContactInfoLocked_nonSensitiveFieldsRemainVisible() {
        User user = User.builder()
                .firstName("Amina")
                .lastName("Belaid")
                .avatarUrl("https://storage.souklab.dz/avatars/amina.jpg")
                .build();
        Artisan artisan = Artisan.builder()
                .id("xyz-9876")
                .user(user)
                .city("Ghardaïa")
                .rating(4.5)
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, true);

        assertThat(dto.getArtisanName()).isEqualTo("Artisan #-9876");
        assertThat(dto.getAvatarUrl()).isEqualTo("https://storage.souklab.dz/avatars/amina.jpg");
        assertThat(dto.getCity()).isEqualTo("Ghardaïa");
        assertThat(dto.getRating()).isZero();
    }


    @Test
    @DisplayName("from: with complete artisan should map all directory card fields accurately")
    void from_withCompleteArtisan_shouldMapAllFieldsAccurately() {
        User user = User.builder()
                .firstName("Djamel")
                .lastName("Amrani")
                .avatarUrl("https://storage.souklab.dz/avatars/djamel.jpg")
                .build();

        Region wilaya = Region.builder()
                .name("Tizi Ouzou")
                .slug("tizi-ouzou")
                .code("15")
                .build();
        wilaya.setId("reg-15");

        JobCategory category = JobCategory.builder()
                .name("Métiers de la Terre")
                .slug("metiers-de-la-terre")
                .build();

        JobSubCategory subCategory = JobSubCategory.builder()
                .name("Poterie & Céramique de Kabylie")
                .slug("poterie-ceramique-kabylie")
                .category(category)
                .build();

        Material clay = Material.builder().name("Argile Rouge de Kabylie").slug("argile-rouge").build();
        Technique woodFiring = Technique.builder().name("Cuisson Traditionnelle au Bois").slug("cuisson-bois").build();

        ArtisanGalleryImage img1 = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/gallery/cover.jpg")
                .displayOrder(0)
                .build();
        ArtisanGalleryImage img2 = ArtisanGalleryImage.builder()
                .imageUrl("https://storage.souklab.dz/gallery/extra.jpg")
                .displayOrder(1)
                .build();

        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0);

        Artisan artisan = Artisan.builder()
                .id("artisan-uuid-123")
                .user(user)
                .region(wilaya)
                .subCategory(subCategory)
                .materials(Set.of(clay))
                .techniques(Set.of(woodFiring))
                .galleryImages(List.of(img2, img1))
                .bio("Maître potier perpétuant l'art ancestral du façonnage à la main de jarres et plats traditionnels.")
                .city("Beni Yenni")
                .rating(0.0)
                .reviewsCount(0)
                .viewsCount(1250)
                .isVerified(true)
                .isPremium(true)
                .isTeacher(true)
                .createdAt(now)
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan, false, true, true);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo("artisan-uuid-123");
        assertThat(dto.getArtisanName()).isEqualTo("Djamel Amrani");
        assertThat(dto.getAvatarUrl()).isEqualTo("https://storage.souklab.dz/avatars/djamel.jpg");
        assertThat(dto.getCoverImageUrl()).isEqualTo("https://storage.souklab.dz/gallery/cover.jpg");
        assertThat(dto.getCity()).isEqualTo("Beni Yenni");
        assertThat(dto.getWilayaName()).isEqualTo("Tizi Ouzou");
        assertThat(dto.getWilayaCode()).isEqualTo("15");
        assertThat(dto.getRegionSlug()).isEqualTo("tizi-ouzou");
        assertThat(dto.getCategoryName()).isEqualTo("Métiers de la Terre");
        assertThat(dto.getCategorySlug()).isEqualTo("metiers-de-la-terre");
        assertThat(dto.getSubCategoryName()).isEqualTo("Poterie & Céramique de Kabylie");
        assertThat(dto.getSubCategorySlug()).isEqualTo("poterie-ceramique-kabylie");
        assertThat(dto.getRating()).isZero();
        assertThat(dto.getReviewsCount()).isZero();
        assertThat(dto.getViewsCount()).isEqualTo(1250);
        assertThat(dto.isVerified()).isTrue();
        assertThat(dto.isPremium()).isTrue();
        assertThat(dto.isTeacher()).isTrue();
        assertThat(dto.getPrimaryMaterials()).containsExactly("Argile Rouge de Kabylie");
        assertThat(dto.getPrimaryTechniques()).containsExactly("Cuisson Traditionnelle au Bois");
        assertThat(dto.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("from: when region is commune should resolve parent Wilaya name and code")
    void from_whenRegionIsCommune_shouldResolveParentWilayaNameAndCode() {
        Region parentWilaya = Region.builder()
                .name("Ghardaïa")
                .slug("ghardaia")
                .code("47")
                .build();

        Region commune = Region.builder()
                .name("Beni Isguen")
                .slug("beni-isguen")
                .code(null)
                .parent(parentWilaya)
                .build();

        Artisan artisan = Artisan.builder()
                .id("artisan-mozabite")
                .region(commune)
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan);

        assertThat(dto).isNotNull();
        assertThat(dto.getWilayaName()).isEqualTo("Ghardaïa");
        assertThat(dto.getWilayaCode()).isEqualTo("47");
        assertThat(dto.getRegionSlug()).isEqualTo("beni-isguen");
    }

    @Test
    @DisplayName("from: with empty associations should provide empty badge lists and null cover")
    void from_withEmptyAssociations_shouldHandleGracefully() {
        Artisan artisan = Artisan.builder()
                .id("minimal-artisan")
                .build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(artisan);

        assertThat(dto).isNotNull();
        assertThat(dto.getPrimaryMaterials()).isEmpty();
        assertThat(dto.getPrimaryTechniques()).isEmpty();
        assertThat(dto.getCoverImageUrl()).isNull();
        assertThat(dto.getArtisanName()).isNull();
        assertThat(dto.getWilayaName()).isNull();
        assertThat(dto.getCategoryName()).isNull();
    }

    @Test
    void from_handlesRootCountryParentsBlankImagesAndBadgeLimits() {
        Region root = Region.builder().name("Algeria").code("DZ").build();
        Region child = Region.builder().name("Algiers").code("16").parent(root).build();
        List<ArtisanGalleryImage> images = List.of(
                ArtisanGalleryImage.builder().imageUrl(" ").displayOrder(0).build(),
                ArtisanGalleryImage.builder().imageUrl(null).displayOrder(1).build());
        Set<Material> materials = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) materials.add(Material.builder().name("m" + i).build());
        Set<Technique> techniques = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) techniques.add(Technique.builder().name("t" + i).build());
        Artisan dtoSource = Artisan.builder().region(child).galleryImages(images)
                .materials(materials).techniques(techniques).build();

        ArtisanDirectoryCardDTO dto = ArtisanDirectoryCardDTO.from(dtoSource);
        assertThat(dto.getWilayaName()).isEqualTo("Algiers");
        assertThat(dto.getWilayaCode()).isEqualTo("16");
        assertThat(dto.getCoverImageUrl()).isNull();
        assertThat(dto.getPrimaryMaterials()).hasSize(4);
        assertThat(dto.getPrimaryTechniques()).hasSize(4);
    }

    @Test
    @DisplayName("truncateBio: when bio is null or blank should return empty string")
    void truncateBio_whenNullOrBlank_shouldReturnEmptyString() {
        assertThat(ArtisanDirectoryCardDTO.truncateBio(null, 100)).isEmpty();
        assertThat(ArtisanDirectoryCardDTO.truncateBio("   ", 100)).isEmpty();
    }

    @Test
    @DisplayName("truncateBio: when bio shorter than maximum should return untruncated trimmed text")
    void truncateBio_whenShorterThanMax_shouldReturnOriginalTrimmed() {
        String bio = "Poterie artisanale berbère.";
        assertThat(ArtisanDirectoryCardDTO.truncateBio(bio, 100)).isEqualTo(bio);
    }

    @Test
    @DisplayName("truncateBio: when bio exceeds maximum should break on clean word boundary with ellipsis")
    void truncateBio_whenExceedsMax_shouldBreakOnWordBoundaryWithEllipsis() {
        String bio = "Atelier de poterie traditionnelle kabyle utilisant des pigments naturels et une cuisson au feu de bois ancestrale.";
        String snippet = ArtisanDirectoryCardDTO.truncateBio(bio, 40);

        assertThat(snippet).endsWith("...");
        assertThat(snippet.length()).isLessThanOrEqualTo(43);
        assertThat(snippet).isEqualTo("Atelier de poterie traditionnelle kabyle...");
    }

    @Test
    @DisplayName("truncateBio: when no space in boundary window should truncate directly at max length with ellipsis")
    void truncateBio_whenNoSpaceInBoundaryWindow_shouldTruncateAtMaxLength() {
        String longWord = "SupercalifragilisticexpialidociousUnbrokenWordExceedingLength";
        String snippet = ArtisanDirectoryCardDTO.truncateBio(longWord, 20);

        assertThat(snippet).isEqualTo("Supercalifragilistic...");
    }
}
