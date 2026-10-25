package com.project.souklab.controller.feed;

import com.jayway.jsonpath.JsonPath;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.User;
import com.project.souklab.service.notification.NotificationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test verifying that feed posts can upload real photos,
 * the public feed returns those photo URLs, and GET /api/v1/files/{key} successfully
 * serves the photo bytes without 404 errors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.search.enabled=false"
})
class FeedPostPhotoIntegrationTest {

    private static final String ARTISAN_EMAIL = "artisan.feed.photo@souklab.dz";
    private static final String ADMIN_EMAIL = "admin.feed.photo@souklab.dz";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private NotificationService notificationService;

    private User artisanUser;
    private Artisan artisanEntity;
    private User adminUser;

    @BeforeEach
    void setUp() {
        artisanUser = User.builder()
                .email(ARTISAN_EMAIL)
                .firstName("Karim")
                .lastName("Meziane")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(artisanUser);

        artisanEntity = Artisan.builder()
                .user(artisanUser)
                .city("Algiers")
                .isVerified(true)
                .isTeacher(false)
                .build();
        entityManager.persist(artisanEntity);
        artisanUser.setArtisan(artisanEntity);

        adminUser = User.builder()
                .email(ADMIN_EMAIL)
                .firstName("Admin")
                .lastName("Moderator")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(adminUser);
        entityManager.flush();
    }

    @Test
    @DisplayName("Feed returns uploaded real photos and GET /api/v1/files/{key} serves photo bytes")
    void feedReturnsPhotosAndServesThemSuccessfully() throws Exception {
        // 1. Resolve real image from user's machine
        Path imagePath = Path.of("/home/walid/dotfiles/wallpaper.jpg");
        byte[] realPhotoBytes;
        String contentType;
        String originalFilename;

        if (Files.exists(imagePath)) {
            realPhotoBytes = Files.readAllBytes(imagePath);
            contentType = "image/jpeg";
            originalFilename = "wallpaper.jpg";
        } else {
            // Fallback valid minimal JPEG if path not found
            realPhotoBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x60,
                    0x00, 0x60, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};
            contentType = "image/jpeg";
            originalFilename = "test_photo.jpg";
        }

        // 2. Submit feed post as verified artisan
        String postPayload = """
                {
                    "type": "ACTUALITE",
                    "title": "Nouvel atelier de poterie traditionnelle",
                    "body": "Découvrez notre nouvelle collection d'artisanat local avec les photos ci-jointes.",
                    "formationId": null
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/feed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postPayload)
                        .with(artisan(ARTISAN_EMAIL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        String postId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");
        assertThat(postId).isNotBlank();

        // 3. Upload the real photo to the post
        MockMultipartFile photoFile = new MockMultipartFile(
                "file",
                originalFilename,
                contentType,
                realPhotoBytes
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/feed/" + postId + "/media")
                        .file(photoFile)
                        .with(artisan(ARTISAN_EMAIL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.url").value(containsString("/api/v1/files/")))
                .andExpect(jsonPath("$.data.contentType").value(contentType))
                .andExpect(jsonPath("$.data.displayOrder").value(0))
                .andReturn();

        String mediaUrl = JsonPath.read(uploadResult.getResponse().getContentAsString(), "$.data.url");
        String storageKey = mediaUrl.substring(mediaUrl.lastIndexOf('/') + 1);
        assertThat(storageKey).isNotBlank();

        // 4. While PENDING: anonymous gets 401, non-author gets 404, author gets access
        mockMvc.perform(get("/api/v1/files/" + storageKey))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/files/" + storageKey)
                        .with(client("other-user@souklab.com")))
                .andExpect(status().isNotFound());

        MvcResult authorAccessResult = mockMvc.perform(get("/api/v1/files/" + storageKey)
                        .with(artisan(ARTISAN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(authorAccessResult))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(content().contentType(contentType))
                .andExpect(content().bytes(realPhotoBytes));

        // 5. Admin publishes the post
        String publishPayload = """
                {
                    "note": "Approved for public craft feed showcase."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/feed/" + postId + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishPayload)
                        .with(admin(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 6. Public feed returns the post with the photo in media array
        mockMvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(postId))
                .andExpect(jsonPath("$.data.content[0].media", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].media[0].url").value(mediaUrl))
                .andExpect(jsonPath("$.data.content[0].media[0].contentType").value(contentType));

        // 7. GET /api/v1/files/{key} is now public to authenticated users (200 OK, public cache, real bytes match)
        MvcResult publicFileResult = mockMvc.perform(get("/api/v1/files/" + storageKey)
                        .with(client("viewer@souklab.com")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(publicFileResult))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
                .andExpect(content().contentType(contentType))
                .andExpect(content().bytes(realPhotoBytes));
    }
}
