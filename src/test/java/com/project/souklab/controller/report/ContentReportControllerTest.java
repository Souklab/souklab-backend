package com.project.souklab.controller.report;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.report.ContentReportResponseDTO;
import com.project.souklab.service.report.ContentReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies report submission and administrator queue authorization.
 */
@ControllerSliceTest(controllers = ContentReportController.class)
class ContentReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentReportService reportService;

    @Test
    void authenticatedUserCanSubmitReport() throws Exception {
        when(reportService.create(any())).thenReturn(ContentReportResponseDTO.builder().id("report-1").build());

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"POST\",\"targetId\":\"post-1\",\"reason\":\"abuse\"}")
                        .with(client()))
                .andExpect(status().isCreated());
    }

    @Test
    void nonAdminCannotListReports() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports").with(client()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListReports() throws Exception {
        when(reportService.list(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/reports").with(admin()))
                .andExpect(status().isOk());
    }
}
