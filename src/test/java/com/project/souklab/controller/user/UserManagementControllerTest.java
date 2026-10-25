package com.project.souklab.controller.user;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.admin.AuditLogDTO;
import com.project.souklab.dto.auth.UserResponseDTO;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.exception.BadRequestException;
import com.project.souklab.exception.ConflictException;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.AuditLogAction;
import com.project.souklab.security.Permission;
import com.project.souklab.service.audit.AuditLogService;
import com.project.souklab.service.user.UserManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for UserManagementController.
 * Verifies class-level ROLE_ADMIN authorization, pagination and query parameter binding,
 * bulk approval ordering and mid-loop error handling, DTO argument extraction,
 * and service exception mapping across all endpoints.
 */
@ControllerSliceTest(controllers = UserManagementController.class)
class UserManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserManagementService userManagementService;

    @MockitoBean
    private AuditLogService auditLogService;

    private UserResponseDTO buildSampleUser(String id, String email, AccountStatus status) {
        return UserResponseDTO.builder()
                .id(id)
                .email(email)
                .firstName("Sample")
                .lastName("User")
                .name("Sample User")
                .status(status)
                .permissions(Set.of(Permission.Profile.READ))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users")
    class GetAllUsersTests {

        /**
         * Verifies that an admin user can retrieve paginated users with default parameters.
         */
        @Test
        @DisplayName("ROLE_ADMIN with default parameters returns 200 OK with paginated users")
        void getAllUsers_withAdminRole_shouldReturnPaginated200Ok() throws Exception {
            UserResponseDTO user = buildSampleUser("u-1", "user1@example.com", AccountStatus.ACTIVE);
            PaginatedResponse<UserResponseDTO> paginatedResponse = PaginatedResponse.<UserResponseDTO>builder()
                    .content(List.of(user))
                    .pageNumber(0)
                    .pageSize(10)
                    .totalElements(1L)
                    .totalPages(1)
                    .last(true)
                    .build();

            when(userManagementService.getAllUsers(isNull(), any(Pageable.class))).thenReturn(paginatedResponse);

            mockMvc.perform(get("/api/v1/admin/users")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("u-1"))
                    .andExpect(jsonPath("$.data.content[0].email").value("user1@example.com"))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userManagementService).getAllUsers(isNull(), pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(10);
            assertThat(captured.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        /**
         * Verifies that custom search query and pagination parameters are correctly forwarded to the service.
         */
        @Test
        @DisplayName("ROLE_ADMIN with search query and custom pagination forwards parameters to service")
        void getAllUsers_withSearchAndCustomPagination_shouldPassParametersToService() throws Exception {
            UserResponseDTO user = buildSampleUser("u-2", "john.doe@example.com", AccountStatus.ACTIVE);
            PaginatedResponse<UserResponseDTO> paginatedResponse = PaginatedResponse.<UserResponseDTO>builder()
                    .content(List.of(user))
                    .pageNumber(1)
                    .pageSize(10)
                    .totalElements(15L)
                    .totalPages(2)
                    .last(true)
                    .build();

            when(userManagementService.getAllUsers(eq("john"), any(Pageable.class))).thenReturn(paginatedResponse);

            mockMvc.perform(get("/api/v1/admin/users")
                            .with(admin())
                            .param("search", "john")
                            .param("page", "1")
                            .param("size", "10")
                            .param("sort", "name,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("u-2"))
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.totalElements").value(15));

            ArgumentCaptor<String> searchCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userManagementService).getAllUsers(searchCaptor.capture(), pageableCaptor.capture());
            assertThat(searchCaptor.getValue()).isEqualTo("john");
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(1);
            assertThat(captured.getPageSize()).isEqualTo(10);
            assertThat(captured.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        /**
         * Verifies that a blank search parameter is forwarded verbatim to the service.
         */
        @Test
        @DisplayName("ROLE_ADMIN with blank search string forwards blank query to service")
        void getAllUsers_withBlankSearch_shouldPassBlankToService() throws Exception {
            PaginatedResponse<UserResponseDTO> paginatedResponse = PaginatedResponse.<UserResponseDTO>builder()
                    .content(Collections.emptyList())
                    .pageNumber(0)
                    .pageSize(10)
                    .totalElements(0L)
                    .totalPages(0)
                    .last(true)
                    .build();

            when(userManagementService.getAllUsers(eq("   "), any(Pageable.class))).thenReturn(paginatedResponse);

            mockMvc.perform(get("/api/v1/admin/users")
                            .with(admin())
                            .param("search", "   "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200));

            verify(userManagementService).getAllUsers(eq("   "), any(Pageable.class));
        }

        /**
         * Verifies that non-admin users (ROLE_ARTISAN) are denied access with 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void getAllUsers_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getAllUsers_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/{id}")
    class GetUserByIdTests {

        @Test
        @DisplayName("ROLE_ADMIN returns 200 OK with single user details")
        void getUserById_withAdminRole_shouldReturn200Ok() throws Exception {
            UserResponseDTO sampleUser = buildSampleUser("u-1", "user@example.com", AccountStatus.ACTIVE);
            when(userManagementService.getUserById("u-1")).thenReturn(sampleUser);

            mockMvc.perform(get("/api/v1/admin/users/u-1")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("u-1"))
                    .andExpect(jsonPath("$.data.email").value("user@example.com"));

            verify(userManagementService).getUserById("u-1");
        }

        @Test
        @DisplayName("non-admin receives 403 Forbidden")
        void getUserById_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users/u-1")
                            .with(client()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/pending")
    class GetPendingUsersTests {

        /**
         * Verifies that an admin user can retrieve pending users with default pagination.
         */
        @Test
        @DisplayName("ROLE_ADMIN with default parameters returns 200 OK with pending users")
        void getPendingUsers_withAdminRole_shouldReturnPaginated200Ok() throws Exception {
            UserResponseDTO pendingUser = buildSampleUser("u-p1", "pending@example.com", AccountStatus.PENDING);
            PaginatedResponse<UserResponseDTO> paginatedResponse = PaginatedResponse.<UserResponseDTO>builder()
                    .content(List.of(pendingUser))
                    .pageNumber(0)
                    .pageSize(10)
                    .totalElements(1L)
                    .totalPages(1)
                    .last(true)
                    .build();

            when(userManagementService.getPendingUsers(any(Pageable.class))).thenReturn(paginatedResponse);

            mockMvc.perform(get("/api/v1/admin/users/pending")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("u-p1"))
                    .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userManagementService).getPendingUsers(pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(10);
            assertThat(captured.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        /**
         * Verifies that custom pagination parameters are correctly forwarded to getPendingUsers.
         */
        @Test
        @DisplayName("ROLE_ADMIN with custom pagination forwards Pageable to service")
        void getPendingUsers_withCustomPagination_shouldPassPageableToService() throws Exception {
            PaginatedResponse<UserResponseDTO> emptyResponse = PaginatedResponse.<UserResponseDTO>builder()
                    .content(Collections.emptyList())
                    .pageNumber(2)
                    .pageSize(5)
                    .totalElements(0L)
                    .totalPages(0)
                    .last(true)
                    .build();

            when(userManagementService.getPendingUsers(any(Pageable.class))).thenReturn(emptyResponse);

            mockMvc.perform(get("/api/v1/admin/users/pending")
                            .with(admin())
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "email,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userManagementService).getPendingUsers(pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(2);
            assertThat(captured.getPageSize()).isEqualTo(5);
            assertThat(captured.getSort().getOrderFor("email").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void getPendingUsers_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users/pending")
                            .with(client()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getPendingUsers_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users/pending"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/audit-logs")
    class GetAuditLogsTests {

        @Test
        @DisplayName("ROLE_ADMIN receives paginated audit records including financial state")
        void getAuditLogs_withAdminRole_shouldReturnPaginatedRecords() throws Exception {
            AuditLogDTO auditLog = AuditLogDTO.builder()
                    .id("audit-1")
                    .action(AuditLogAction.Subscription.GRANTED)
                    .details("subscription granted")
                    .userId("admin-1")
                    .userEmail("admin@souklab.test")
                    .targetAccountId("client-1")
                    .operation("MANUAL_GRANT")
                    .previousState("NONE")
                    .newState("ACTIVE")
                    .reason("verification")
                    .paymentId("payment-1")
                    .subscriptionId("subscription-1")
                    .build();
            PaginatedResponse<AuditLogDTO> response = PaginatedResponse.<AuditLogDTO>builder()
                    .content(List.of(auditLog))
                    .pageNumber(0)
                    .pageSize(10)
                    .totalElements(1)
                    .totalPages(1)
                    .last(true)
                    .build();
            when(auditLogService.getAuditLogs(any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get("/api/v1/admin/users/audit-logs").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].action").value("SUBSCRIPTION_GRANTED"))
                    .andExpect(jsonPath("$.data.content[0].targetAccountId").value("client-1"))
                    .andExpect(jsonPath("$.data.content[0].newState").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.content[0].paymentId").value("payment-1"));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(auditLogService).getAuditLogs(pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                    .isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("non-admin users cannot read audit records")
        void getAuditLogs_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users/audit-logs").with(client()))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("unauthenticated requests cannot read audit records")
        void getAuditLogs_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/admin/users/audit-logs"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(auditLogService);
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/approve")
    class ApproveUserTests {

        /**
         * Verifies that an admin can approve a user, returning 200 OK with null data and success message.
         */
        @Test
        @DisplayName("ROLE_ADMIN approves user and returns 200 OK")
        void approveUser_withAdminRole_shouldApproveAndReturn200Ok() throws Exception {
            doNothing().when(userManagementService).approveUser("u-10");

            mockMvc.perform(post("/api/v1/admin/users/u-10/approve")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("User approved successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(userManagementService).approveUser("u-10");
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("user not found maps to 404 Not Found")
        void approveUser_whenUserNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("User not found with id: u-999"))
                    .when(userManagementService).approveUser("u-999");

            mockMvc.perform(post("/api/v1/admin/users/u-999/approve")
                            .with(admin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("User not found with id: u-999"));
        }

        /**
         * Verifies that BadRequestException (e.g. user already active) maps to 400 Bad Request.
         */
        @Test
        @DisplayName("user not pending approval maps to 400 Bad Request")
        void approveUser_whenUserNotPending_shouldReturn400BadRequest() throws Exception {
            doThrow(new BadRequestException("User is not pending approval. Current status: ACTIVE"))
                    .when(userManagementService).approveUser("u-10");

            mockMvc.perform(post("/api/v1/admin/users/u-10/approve")
                            .with(admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("User is not pending approval. Current status: ACTIVE"));
        }

        /**
         * Verifies that ROLE_ARTISAN receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void approveUser_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-10/approve")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void approveUser_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-10/approve"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users/approve-bulk")
    class ApproveUsersBulkTests {

        /**
         * Verifies that multiple user IDs trigger approveUser sequentially in order, once per ID.
         */
        @Test
        @DisplayName("ROLE_ADMIN with multiple IDs approves each user sequentially in order")
        void approveUsersBulk_withMultipleIds_shouldCallServicePerIdInOrderAndReturn200Ok() throws Exception {
            doNothing().when(userManagementService).approveUser(anyString());

            mockMvc.perform(post("/api/v1/admin/users/approve-bulk")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"u-1\",\"u-2\",\"u-3\"]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Users approved successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            InOrder inOrder = inOrder(userManagementService);
            inOrder.verify(userManagementService).approveUser("u-1");
            inOrder.verify(userManagementService).approveUser("u-2");
            inOrder.verify(userManagementService).approveUser("u-3");
            inOrder.verifyNoMoreInteractions();
        }

        /**
         * Verifies that an empty list of IDs succeeds with 200 OK without invoking approveUser.
         */
        @Test
        @DisplayName("ROLE_ADMIN with empty list succeeds without calling service")
        void approveUsersBulk_withEmptyList_shouldNotCallServiceAndReturn200Ok() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/approve-bulk")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Users approved successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verifyNoInteractions(userManagementService);
        }

        /**
         * Verifies that when a service exception occurs mid-loop on id #2,
         * the controller aborts and id #3 is never attempted.
         */
        @Test
        @DisplayName("mid-loop exception on id #2 aborts iteration, preventing id #3 from being attempted")
        void approveUsersBulk_whenMidLoopThrowsException_shouldAbortSubsequentIdsAndReturn400BadRequest() throws Exception {
            doNothing().when(userManagementService).approveUser("u-1");
            doThrow(new BadRequestException("User is not pending approval. Current status: ACTIVE"))
                    .when(userManagementService).approveUser("u-2");

            mockMvc.perform(post("/api/v1/admin/users/approve-bulk")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"u-1\",\"u-2\",\"u-3\"]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("User is not pending approval. Current status: ACTIVE"));

            InOrder inOrder = inOrder(userManagementService);
            inOrder.verify(userManagementService).approveUser("u-1");
            inOrder.verify(userManagementService).approveUser("u-2");
            verify(userManagementService, never()).approveUser("u-3");
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void approveUsersBulk_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/approve-bulk")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"u-1\"]"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void approveUsersBulk_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/approve-bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"u-1\"]"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/ban")
    class BanUserTests {

        /**
         * Verifies that an admin can ban a user with an explicit reason.
         */
        @Test
        @DisplayName("ROLE_ADMIN bans user with explicit reason and returns 200 OK")
        void banUser_withAdminRoleAndExplicitReason_shouldPassReasonAndReturn200Ok() throws Exception {
            doNothing().when(userManagementService).banUser(eq("u-20"), anyString());

            mockMvc.perform(post("/api/v1/admin/users/u-20/ban")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Fraudulent marketplace activity\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("User banned successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(userManagementService).banUser(eq("u-20"), reasonCaptor.capture());
            assertThat(reasonCaptor.getValue()).isEqualTo("Fraudulent marketplace activity");
        }

        /**
         * Verifies that a blank reason is rejected by Bean Validation (@NotBlank) with
         * 422 Unprocessable Content, and the service is never called.
         */
        @Test
        @DisplayName("blank reason rejected by @NotBlank with 422 Unprocessable Content")
        void banUser_withBlankReason_shouldReturn422UnprocessableContent() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-20/ban")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.reason").exists());

            verifyNoInteractions(userManagementService);
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("user not found maps to 404 Not Found")
        void banUser_whenUserNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("User not found with id: u-999"))
                    .when(userManagementService).banUser(eq("u-999"), any());

            mockMvc.perform(post("/api/v1/admin/users/u-999/ban")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Terms violation\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void banUser_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-20/ban")
                            .with(client())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Terms violation\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void banUser_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-20/ban")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Terms violation\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/timeout")
    class TimeoutUserTests {

        /**
         * Verifies that an admin can timeout a user with valid minutes and reason.
         */
        @Test
        @DisplayName("ROLE_ADMIN times out user with valid minutes and reason returning 200 OK")
        void timeoutUser_withAdminRoleAndValidRequest_shouldPassMinutesAndReasonAndReturn200Ok() throws Exception {
            doNothing().when(userManagementService).timeoutUser(eq("u-30"), anyInt(), anyString());

            mockMvc.perform(post("/api/v1/admin/users/u-30/timeout")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":120,\"reason\":\"Spam in discussions\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("User timed out successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            ArgumentCaptor<Integer> minutesCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(userManagementService).timeoutUser(eq("u-30"), minutesCaptor.capture(), reasonCaptor.capture());
            assertThat(minutesCaptor.getValue()).isEqualTo(120);
            assertThat(reasonCaptor.getValue()).isEqualTo("Spam in discussions");
        }

        /**
         * Verifies that non-positive minutes are rejected by Bean Validation ({@code @Positive}) with
         * 422 Unprocessable Content. The service is never called because the constraint fires first.
         * The service-layer {@code BadRequestException} path for minutes &lt;= 0 is proven separately
         * in {@code UserManagementServiceTest}.
         */
        @Test
        @DisplayName("non-positive minutes rejected by @Positive with 422 Unprocessable Content")
        void timeoutUser_whenNonPositiveMinutes_shouldReturn422UnprocessableContent() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-30/timeout")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":0,\"reason\":\"Invalid duration\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.errors.minutes").exists());

            verifyNoInteractions(userManagementService);
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("user not found maps to 404 Not Found")
        void timeoutUser_whenUserNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("User not found with id: u-999"))
                    .when(userManagementService).timeoutUser(eq("u-999"), anyInt(), any());

            mockMvc.perform(post("/api/v1/admin/users/u-999/timeout")
                            .with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":60,\"reason\":\"Testing\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that ROLE_ARTISAN receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void timeoutUser_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-30/timeout")
                            .with(artisan())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":60,\"reason\":\"Testing\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void timeoutUser_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-30/timeout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"minutes\":60,\"reason\":\"Testing\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/admin/users/{id}/unban")
    class UnbanUserTests {

        /**
         * Verifies that an admin user can reinstate a suspended user and receive 200 OK.
         */
        @Test
        @DisplayName("ROLE_ADMIN unbans user and returns 200 OK")
        void unbanUser_withAdminRole_shouldReturn200Ok() throws Exception {
            doNothing().when(userManagementService).unbanUser("u-40");

            mockMvc.perform(post("/api/v1/admin/users/u-40/unban")
                            .with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("User unbanned successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(userManagementService).unbanUser("u-40");
        }

        /**
         * Verifies that unbanning a user who is not suspended throws ConflictException and returns 409 Conflict.
         */
        @Test
        @DisplayName("unbanning an already active user returns 409 Conflict")
        void unbanUser_whenUserNotSuspended_shouldReturn409Conflict() throws Exception {
            doThrow(new ConflictException("User is not suspended. Current status: ACTIVE"))
                    .when(userManagementService).unbanUser("u-40");

            mockMvc.perform(post("/api/v1/admin/users/u-40/unban")
                            .with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("User is not suspended. Current status: ACTIVE"));
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("user not found maps to 404 Not Found")
        void unbanUser_whenUserNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("User not found with id: u-999"))
                    .when(userManagementService).unbanUser("u-999");

            mockMvc.perform(post("/api/v1/admin/users/u-999/unban")
                            .with(admin()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that ROLE_CLIENT receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_CLIENT receives 403 Forbidden")
        void unbanUser_withClientRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-40/unban")
                            .with(client()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that ROLE_ARTISAN receives 403 Forbidden.
         */
        @Test
        @DisplayName("ROLE_ARTISAN receives 403 Forbidden")
        void unbanUser_withArtisanRole_shouldReturn403Forbidden() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-40/unban")
                            .with(artisan()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void unbanUser_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/users/u-40/unban"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }
}
