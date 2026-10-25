package com.project.souklab.controller.formation;

import com.jayway.jsonpath.JsonPath;
import com.project.souklab.dao.ArtisanRepository;
import com.project.souklab.dao.ClientRepository;
import com.project.souklab.dao.UserRepository;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.Artisan;
import com.project.souklab.model.Client;
import com.project.souklab.model.ClientType;
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


import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for Phase 6: Formations and Masterclasses.
 * Verifies the complete lifecycle across relational persistence:
 * draft authoring, media uploads, administrative moderation review,
 * peer enrollment with capacity enforcement, cancellation cutoff policies,
 * protected syllabus file access, and strict client boundary protections.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "app.search.enabled=false"
})
class FormationIntegrationTest {

    private static final String MASTER_TEACHER_EMAIL = "master.teacher.integration@souklab.dz";
    private static final String PEER_ARTISAN_1_EMAIL = "peer.artisan1.integration@souklab.dz";
    private static final String PEER_ARTISAN_2_EMAIL = "peer.artisan2.integration@souklab.dz";
    private static final String ADMIN_EMAIL = "admin.formation.integration@souklab.dz";
    private static final String CLIENT_EMAIL = "client.user.integration@souklab.dz";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ArtisanRepository artisanRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private NotificationService notificationService;

    private User teacherUser;
    private Artisan teacherArtisan;
    private User peerUser1;
    private Artisan peerArtisan1;
    private User peerUser2;
    private Artisan peerArtisan2;
    private User adminUser;
    private User clientUser;
    private Client clientEntity;

    @BeforeEach
    void setUpEntities() {
        teacherUser = User.builder()
                .email(MASTER_TEACHER_EMAIL)
                .firstName("Rabah")
                .lastName("Maitre")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(teacherUser);

        teacherArtisan = Artisan.builder()
                .user(teacherUser)
                .city("Algiers")
                .isTeacher(true)
                .isVerified(true)
                .build();
        entityManager.persist(teacherArtisan);
        teacherUser.setArtisan(teacherArtisan);

        peerUser1 = User.builder()
                .email(PEER_ARTISAN_1_EMAIL)
                .firstName("Lyes")
                .lastName("Artisan")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(peerUser1);

        peerArtisan1 = Artisan.builder()
                .user(peerUser1)
                .city("Tizi Ouzou")
                .isTeacher(false)
                .build();
        entityManager.persist(peerArtisan1);
        peerUser1.setArtisan(peerArtisan1);

        peerUser2 = User.builder()
                .email(PEER_ARTISAN_2_EMAIL)
                .firstName("Kamel")
                .lastName("Artisan")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(peerUser2);

        peerArtisan2 = Artisan.builder()
                .user(peerUser2)
                .city("Constantine")
                .isTeacher(false)
                .build();
        entityManager.persist(peerArtisan2);
        peerUser2.setArtisan(peerArtisan2);

        adminUser = User.builder()
                .email(ADMIN_EMAIL)
                .firstName("Admin")
                .lastName("Moderator")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(adminUser);

        clientUser = User.builder()
                .email(CLIENT_EMAIL)
                .firstName("Samy")
                .lastName("Client")
                .status(AccountStatus.ACTIVE)
                .emailVerified(true)
                .build();
        entityManager.persist(clientUser);

        clientEntity = Client.builder()
                .user(clientUser)
                .clientType(ClientType.INDIVIDUAL.value())
                .city("Oran")
                .build();
        entityManager.persist(clientEntity);
        clientUser.setClient(clientEntity);

        entityManager.flush();
    }

    /**
     * Records an administrative approval review decision for the specified formation.
     *
     * @param formationId formation unique identifier
     * @throws Exception if mockMvc request execution fails
     */
    private void approveFormation(String formationId) throws Exception {
        String approvePayload = """
                {
                    "decision": "APPROVED",
                    "comment": "Approved by administrator."
                }
                """;
        mockMvc.perform(post("/api/v1/admin/formations/" + formationId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvePayload)
                        .with(admin(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    /**
     * Publishes an approved formation to the public peer catalog.
     *
     * @param formationId formation unique identifier
     * @throws Exception if mockMvc request execution fails
     */
    private void publishFormation(String formationId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/formations/" + formationId + "/publish")
                        .with(admin(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    /**
     * Scenario 1: Exercises complete authoring, upload, review, approval, publication,
     * and appearance in the peer catalog.
     */
    @Test
    @DisplayName("Scenario 1: Complete masterclass lifecycle from draft authoring to catalog publication")
    void testFullFormationAuthoringAndPublishingLifecycle() throws Exception {
        String draftPayload = """
                {
                    "title": "Mastering Traditional Pottery",
                    "description": "Comprehensive ceramics masterclass focusing on ancestral clay pottery.",
                    "location": "Beni Yenni, Tizi Ouzou",
                    "isOnline": false,
                    "scheduledAt": "2030-05-15T10:00:00",
                    "durationHours": 8,
                    "maxParticipants": 5,
                    "price": 12000
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftPayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.title").value("Mastering Traditional Pottery"))
                .andReturn();

        String formationId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");

        MockMultipartFile thumbnailFile = new MockMultipartFile(
                "file",
                "thumbnail.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10}
        );

        mockMvc.perform(multipart("/api/v1/artisan/formations/" + formationId + "/thumbnail")
                        .file(thumbnailFile)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.thumbnailUrl").value(containsString("/api/v1/files/")));

        MockMultipartFile syllabusFile = new MockMultipartFile(
                "file",
                "syllabus.pdf",
                "application/pdf",
                "%PDF-1.4 sample content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/artisan/formations/" + formationId + "/files")
                        .file(syllabusFile)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.originalFilename").value("syllabus.pdf"));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit")
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/api/v1/admin/formations/pending")
                        .with(admin(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(formationId)));

        approveFormation(formationId);
        publishFormation(formationId);

        mockMvc.perform(get("/api/v1/artisan/formations/catalog")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[*].id").value(hasItem(formationId)));
    }

    /**
     * Scenario 2: Exercises administrative rejection workflow with required review comment,
     * subsequent author correction, re-submission, and eventual approval.
     */
    @Test
    @DisplayName("Scenario 2: Administrative rejection workflow, author correction, and approval")
    void testModerationRejectionAndCorrectionWorkflow() throws Exception {
        String draftPayload = """
                {
                    "title": "Ancestral Wood Inlay",
                    "description": "Intensive wood inlaying workshop.",
                    "location": "Constantine",
                    "isOnline": false,
                    "scheduledAt": "2030-06-01T09:00:00",
                    "durationHours": 6,
                    "maxParticipants": 4,
                    "price": 10000
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftPayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();

        String formationId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit")
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk());

        String rejectPayload = """
                {
                    "decision": "REJECTED",
                    "comment": "Please provide more details on safety equipment required."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/formations/" + formationId + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejectPayload)
                        .with(admin(ADMIN_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        String updatePayload = """
                {
                    "title": "Ancestral Wood Inlay (Updated)",
                    "description": "Intensive wood inlaying workshop. Safety glasses and gloves provided.",
                    "location": "Constantine Workshop",
                    "isOnline": false,
                    "scheduledAt": "2030-06-01T09:00:00",
                    "durationHours": 6,
                    "maxParticipants": 4,
                    "price": 10000
                }
                """;

        mockMvc.perform(put("/api/v1/artisan/formations/" + formationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit")
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        approveFormation(formationId);
    }

    /**
     * Scenario 3: Exercises peer artisan enrollment, available seats calculation,
     * and capacity overflow rejection when maxParticipants is reached.
     */
    @Test
    @DisplayName("Scenario 3: Peer enrollment, dynamic seat decrement, and capacity overflow 409 Conflict")
    void testPeerEnrollmentAndCapacityEnforcement() throws Exception {
        String draftPayload = """
                {
                    "title": "Exclusive Jewelry Masterclass",
                    "description": "One-on-one silver filigree jewelry techniques.",
                    "location": "Beni Yenni",
                    "isOnline": false,
                    "scheduledAt": "2030-07-10T10:00:00",
                    "durationHours": 5,
                    "maxParticipants": 1,
                    "price": 25000
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftPayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();

        String formationId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit").with(artisan(MASTER_TEACHER_EMAIL)));
        approveFormation(formationId);
        publishFormation(formationId);

        mockMvc.perform(get("/api/v1/artisan/formations/catalog/" + formationId)
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableSeats").value(1))
                .andExpect(jsonPath("$.data.isEnrolled").value(false));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/enroll")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/artisan/formations/catalog/" + formationId)
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableSeats").value(0))
                .andExpect(jsonPath("$.data.isEnrolled").value(true));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/enroll")
                        .with(artisan(PEER_ARTISAN_2_EMAIL)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Maximum capacity reached")));
    }

    /**
     * Scenario 4: Exercises enrollment cancellation before the deadline, available seat recovery,
     * and seamless seat re-activation upon re-enrolling without primary key collisions.
     */
    @Test
    @DisplayName("Scenario 4: Cancellation and seat reactivation without duplicate key collisions")
    void testCancellationAndReactivation() throws Exception {
        String draftPayload = """
                {
                    "title": "Copper Chasing Session",
                    "description": "Traditional copper chasing and ornamentation.",
                    "location": "Algiers Casbah",
                    "isOnline": false,
                    "scheduledAt": "2030-08-01T10:00:00",
                    "durationHours": 4,
                    "maxParticipants": 2,
                    "price": 8000
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftPayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();

        String formationId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit").with(artisan(MASTER_TEACHER_EMAIL)));
        approveFormation(formationId);
        publishFormation(formationId);

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/enroll")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/cancel")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/artisan/formations/catalog/" + formationId)
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableSeats").value(2))
                .andExpect(jsonPath("$.data.isEnrolled").value(false));

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/enroll")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/artisan/formations/catalog/" + formationId)
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableSeats").value(1))
                .andExpect(jsonPath("$.data.isEnrolled").value(true));
    }

    /**
     * Scenario 5: Exercises syllabus file download permissions.
     * Enrolled participant and author instructor are allowed; non-enrolled peer is rejected with 403.
     */
    @Test
    @DisplayName("Scenario 5: Protected course syllabus file access restrictions")
    void testProtectedCourseFileSecurity() throws Exception {
        String draftPayload = """
                {
                    "title": "Mastering Leather Stamping",
                    "description": "Ancestral leather carving and stamping techniques.",
                    "location": "Algiers",
                    "isOnline": false,
                    "scheduledAt": "2030-09-01T10:00:00",
                    "durationHours": 5,
                    "maxParticipants": 5,
                    "price": 9000
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftPayload)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();

        String formationId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.data.id");

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "syllabus_tools.pdf",
                "application/pdf",
                "%PDF-1.4 test document content".getBytes()
        );

        MvcResult fileResult = mockMvc.perform(multipart("/api/v1/artisan/formations/" + formationId + "/files")
                        .file(file)
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();

        String fileId = JsonPath.read(fileResult.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/submit").with(artisan(MASTER_TEACHER_EMAIL)));
        approveFormation(formationId);
        publishFormation(formationId);

        mockMvc.perform(get("/api/v1/artisan/formations/" + formationId + "/files/" + fileId + "/download")
                        .with(artisan(MASTER_TEACHER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("syllabus_tools.pdf")));

        mockMvc.perform(get("/api/v1/artisan/formations/" + formationId + "/files/" + fileId + "/download")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/artisan/formations/" + formationId + "/enroll")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/artisan/formations/" + formationId + "/files/" + fileId + "/download")
                        .with(artisan(PEER_ARTISAN_1_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("syllabus_tools.pdf")));
    }

    /**
     * Scenario 6: Verifies that authenticated clients (ROLE_CLIENT) are rejected with 403 Forbidden
     * across all formation authoring, catalog, enrollment, and administrative moderation routes.
     */
    @Test
    @DisplayName("Scenario 6: Strict peer-learning boundary rejecting client role access with 403 Forbidden")
    void testClientBoundaryRejections() throws Exception {
        String clientDraftPayload = """
                {
                    "title": "Unauthorized Client Masterclass",
                    "description": "Client attempting to author masterclass.",
                    "location": "Algiers",
                    "isOnline": false,
                    "scheduledAt": "2030-10-01T10:00:00",
                    "durationHours": 4,
                    "maxParticipants": 5,
                    "price": 5000
                }
                """;
        mockMvc.perform(post("/api/v1/artisan/formations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientDraftPayload)
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/artisan/formations/catalog")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/artisan/formations/catalog/sample-id")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/artisan/formations/sample-id/enroll")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/artisan/formations/sample-id/cancel")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/artisan/formations/my-enrollments")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/formations/pending")
                        .with(client(CLIENT_EMAIL)))
                .andExpect(status().isForbidden());
    }
}
