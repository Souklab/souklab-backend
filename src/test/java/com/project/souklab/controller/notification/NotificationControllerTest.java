package com.project.souklab.controller.notification;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.notification.NotificationResponseDTO;
import com.project.souklab.exception.ResourceNotFoundException;
import com.project.souklab.model.NotificationType;
import com.project.souklab.service.notification.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller slice tests for NotificationController.
 * Verifies authentication enforcement, endpoint routing, pagination parameter binding,
 * path-variable extraction, service delegation, and ApiResponse encapsulation across all 5 endpoints.
 *
 * Note on user identity resolution:
 * NotificationController contains zero principal-resolving or branching logic;
 * NotificationService resolves the authenticated caller directly via SecurityUtils.getCurrentUsername().
 * Slice tests verify that authenticated requests successfully route and delegate,
 * while unauthenticated requests are uniformly rejected with 401 Unauthorized by the filter chain.
 */
@ControllerSliceTest(controllers = NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    private NotificationResponseDTO buildSampleNotification(String id, String message, boolean isRead) {
        return NotificationResponseDTO.builder()
                .id(id)
                .message(message)
                .isRead(isRead)
                .type(NotificationType.Account.VALIDATED)
                .targetId("target-123")
                .createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }

    @Nested
    @DisplayName("GET /api/v1/notifications")
    class GetNotificationsTests {

        /**
         * Verifies that authenticated user can retrieve paginated notifications with default parameters.
         */
        @Test
        @DisplayName("authenticated user retrieves paginated notifications returning 200 OK")
        void getNotifications_whenAuthenticated_shouldReturnPaginated200Ok() throws Exception {
            NotificationResponseDTO notification = buildSampleNotification("notif-1", "Your account has been approved", false);
            PaginatedResponse<NotificationResponseDTO> paginatedResponse = PaginatedResponse.<NotificationResponseDTO>builder()
                    .content(List.of(notification))
                    .pageNumber(0)
                    .pageSize(20)
                    .totalElements(1L)
                    .totalPages(1)
                    .last(true)
                    .build();

            when(notificationService.getCurrentUserNotifications(any(Pageable.class))).thenReturn(paginatedResponse);

            mockMvc.perform(get("/api/v1/notifications")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content[0].id").value("notif-1"))
                    .andExpect(jsonPath("$.data.content[0].message").value("Your account has been approved"))
                    .andExpect(jsonPath("$.data.content[0].read").value(false))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(20));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationService).getCurrentUserNotifications(pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
        }

        /**
         * Verifies that custom pagination and sorting parameters are forwarded to NotificationService.
         */
        @Test
        @DisplayName("authenticated user with custom pagination forwards Pageable to service")
        void getNotifications_withCustomPagination_shouldPassPageableToService() throws Exception {
            PaginatedResponse<NotificationResponseDTO> emptyResponse = PaginatedResponse.<NotificationResponseDTO>builder()
                    .content(List.of())
                    .pageNumber(1)
                    .pageSize(10)
                    .totalElements(15L)
                    .totalPages(2)
                    .last(true)
                    .build();

            when(notificationService.getCurrentUserNotifications(any(Pageable.class))).thenReturn(emptyResponse);

            mockMvc.perform(get("/api/v1/notifications")
                            .with(client())
                            .param("page", "1")
                            .param("size", "10")
                            .param("sort", "createdAt,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pageNumber").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(notificationService).getCurrentUserNotifications(pageableCaptor.capture());
            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(1);
            assertThat(captured.getPageSize()).isEqualTo(10);
            assertThat(captured.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getNotifications_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/notifications"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/notifications/unread-count")
    class GetUnreadCountTests {

        /**
         * Verifies that an authenticated user can retrieve their unread notification count.
         */
        @Test
        @DisplayName("authenticated user retrieves unread count returning 200 OK")
        void getUnreadCount_whenAuthenticated_shouldReturnCountAnd200Ok() throws Exception {
            when(notificationService.getUnreadCount()).thenReturn(5L);

            mockMvc.perform(get("/api/v1/notifications/unread-count")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value(5));

            verify(notificationService).getUnreadCount();
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getUnreadCount_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/notifications/unread-count"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/notifications/{id}/read")
    class MarkAsReadTests {

        /**
         * Verifies that an authenticated user can mark a specific notification as read.
         */
        @Test
        @DisplayName("authenticated user marks notification as read returning 200 OK")
        void markAsRead_whenAuthenticated_shouldReturnUpdatedNotificationAnd200Ok() throws Exception {
            NotificationResponseDTO updated = buildSampleNotification("notif-10", "Order confirmed", true);
            when(notificationService.markAsRead(eq("notif-10"))).thenReturn(updated);

            mockMvc.perform(put("/api/v1/notifications/notif-10/read")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("notif-10"))
                    .andExpect(jsonPath("$.data.message").value("Order confirmed"))
                    .andExpect(jsonPath("$.data.read").value(true))
                    .andExpect(jsonPath("$.data.type").value("ACCOUNT_VALIDATED"))
                    .andExpect(jsonPath("$.data.targetId").value("target-123"));

            verify(notificationService).markAsRead("notif-10");
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("notification not found maps to 404 Not Found")
        void markAsRead_whenNotFound_shouldReturn404NotFound() throws Exception {
            when(notificationService.markAsRead(eq("notif-999")))
                    .thenThrow(new ResourceNotFoundException("Notification not found with id: notif-999"));

            mockMvc.perform(put("/api/v1/notifications/notif-999/read")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("Notification not found with id: notif-999"));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void markAsRead_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(put("/api/v1/notifications/notif-10/read"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/notifications/read-all")
    class MarkAllAsReadTests {

        /**
         * Verifies that an authenticated user can mark all notifications as read.
         */
        @Test
        @DisplayName("authenticated user marks all notifications as read returning 200 OK")
        void markAllAsRead_whenAuthenticated_shouldCallServiceAndReturn200Ok() throws Exception {
            doNothing().when(notificationService).markAllAsRead();

            mockMvc.perform(put("/api/v1/notifications/read-all")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("All notifications marked as read"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(notificationService).markAllAsRead();
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void markAllAsRead_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(put("/api/v1/notifications/read-all"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/notifications/{id}")
    class DeleteNotificationTests {

        /**
         * Verifies that an authenticated user can delete a specific notification.
         */
        @Test
        @DisplayName("authenticated user deletes notification returning 200 OK")
        void deleteNotification_whenAuthenticated_shouldCallServiceAndReturn200Ok() throws Exception {
            doNothing().when(notificationService).deleteNotification(eq("notif-20"));

            mockMvc.perform(delete("/api/v1/notifications/notif-20")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("Notification deleted successfully"))
                    .andExpect(jsonPath("$.data").value(nullValue()));

            verify(notificationService).deleteNotification("notif-20");
        }

        /**
         * Verifies that ResourceNotFoundException maps to 404 Not Found.
         */
        @Test
        @DisplayName("notification not found maps to 404 Not Found")
        void deleteNotification_whenNotFound_shouldReturn404NotFound() throws Exception {
            doThrow(new ResourceNotFoundException("Notification not found with id: notif-999"))
                    .when(notificationService).deleteNotification(eq("notif-999"));

            mockMvc.perform(delete("/api/v1/notifications/notif-999")
                            .with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(404));
        }

        /**
         * Verifies that unauthenticated request receives 401 Unauthorized.
         */
        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void deleteNotification_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(delete("/api/v1/notifications/notif-20"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/notifications/{id}")
    class GetSingleNotificationEndpointTests {

        @Test
        @DisplayName("authenticated user can retrieve notification by ID")
        void getNotification_authenticated_shouldReturn200Ok() throws Exception {
            NotificationResponseDTO responseDTO = buildSampleNotification("notif-1", "Test message", false);
            when(notificationService.getNotification("notif-1")).thenReturn(responseDTO);

            mockMvc.perform(get("/api/v1/notifications/notif-1")
                            .with(client()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("notif-1"))
                    .andExpect(jsonPath("$.data.message").value("Test message"));

            verify(notificationService).getNotification("notif-1");
        }

        @Test
        @DisplayName("unauthenticated request receives 401 Unauthorized")
        void getNotification_unauthenticated_shouldReturn401Unauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/notifications/notif-1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }
}
