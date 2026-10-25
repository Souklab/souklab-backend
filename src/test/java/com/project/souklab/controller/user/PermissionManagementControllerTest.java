package com.project.souklab.controller.user;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.security.Permission;
import com.project.souklab.service.auth.PermissionManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ControllerSliceTest(controllers = PermissionManagementController.class)
class PermissionManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PermissionManagementService permissionManagementService;

    @Test
    void adminCanListGrantAndRevokePermissions() throws Exception {
        when(permissionManagementService.list("user-1")).thenReturn(Set.of(Permission.Profile.READ));
        when(permissionManagementService.grant(eq("user-1"), any())).thenReturn(Set.of(Permission.Profile.READ, Permission.Profile.WRITE));
        when(permissionManagementService.revoke(eq("user-1"), any())).thenReturn(Set.of(Permission.Profile.READ));

        mockMvc.perform(get("/api/v1/admin/users/user-1/permissions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(Permission.Profile.READ.value()));
        String request = "{\"permissionKey\":\"" + Permission.Profile.WRITE.value() + "\"}";
        mockMvc.perform(post("/api/v1/admin/users/user-1/permissions").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasItem(Permission.Profile.WRITE.value())));
        mockMvc.perform(delete("/api/v1/admin/users/user-1/permissions").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Permission revoked."));
    }

    @Test
    void nonAdminCannotManagePermissions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/user-1/permissions").with(client()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPermissionAssignmentIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/user-1/permissions").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permissionKey\":\"\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
