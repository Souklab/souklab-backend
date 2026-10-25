package com.project.souklab.controller.feed;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.dto.common.PaginatedResponse;
import com.project.souklab.dto.feed.FeedPostCommentResponseDTO;
import com.project.souklab.dto.feed.FeedPostCreateDTO;
import com.project.souklab.dto.feed.FeedPostLikeStatusDTO;
import com.project.souklab.dto.feed.FeedPostLikerDTO;
import com.project.souklab.dto.feed.FeedPostMediaResponseDTO;
import com.project.souklab.dto.feed.FeedPostResponseDTO;
import com.project.souklab.service.feed.FeedPostService;
import com.project.souklab.service.feed.FeedEngagementService;
import com.project.souklab.service.feed.FeedDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.project.souklab.controller.support.SecurityTestUtils.admin;
import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Verifies public feed routing and administrator moderation authorization.
 */
@ControllerSliceTest(controllers = {FeedPostController.class, AdminFeedController.class})
class FeedPostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedPostService feedPostService;

    @MockitoBean
    private FeedEngagementService feedEngagementService;

    @MockitoBean
    private FeedDiscoveryService feedDiscoveryService;

    @Test
    void publicFeedReturnsPublishedPosts() throws Exception {
        when(feedPostService.listPublic(any(), any())).thenReturn(new PageImpl<>(List.of(FeedPostResponseDTO.builder().id("post-1").build())));

        mockMvc.perform(get("/api/v1/feed").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value("post-1"));
    }

    @Test
    void publicFeedCanRetrieveOnePost() throws Exception {
        when(feedPostService.getForCaller("post-1")).thenReturn(FeedPostResponseDTO.builder().id("post-1").build());

        mockMvc.perform(get("/api/v1/feed/post-1").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("post-1"));
    }

    @Test
    void authenticatedUserCanCreateUpdateRemoveAndManageMedia() throws Exception {
        FeedPostResponseDTO response = FeedPostResponseDTO.builder().id("post-1").build();
        when(feedPostService.create(any())).thenReturn(response);
        when(feedPostService.update(any(), any())).thenReturn(response);
        when(feedPostService.addMedia(any(), any())).thenReturn(FeedPostMediaResponseDTO.builder().id("media-1").build());

        String body = "{\"type\":\"ACTUALITE\",\"title\":\"Title\",\"body\":\"Body\"}";
        mockMvc.perform(post("/api/v1/feed").contentType("application/json").content(body).with(artisan()))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/feed/post-1").contentType("application/json").content(body).with(artisan()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/feed/post-1").with(artisan()))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/v1/feed/post-1/media")
                        .file(new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1}))
                        .with(artisan()))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/feed/post-1/media/media-1").with(artisan()))
                .andExpect(status().isOk());
    }

    @Test
    void bindsDraftFlagFromHttpPayload() throws Exception {
        when(feedPostService.create(any())).thenReturn(FeedPostResponseDTO.builder().id("draft-1").build());

        mockMvc.perform(post("/api/v1/feed").contentType("application/json")
                        .content("{\"type\":\"ACTUALITE\",\"title\":\"Draft\",\"body\":\"Body\",\"isDraft\":true}")
                        .with(artisan()))
                .andExpect(status().isCreated());

        ArgumentCaptor<FeedPostCreateDTO> request = ArgumentCaptor.forClass(FeedPostCreateDTO.class);
        verify(feedPostService).create(request.capture());
        assertThat(request.getValue().isDraft()).isTrue();
    }

    @Test
    void supportsSingleCommentReadAndUpdate() throws Exception {
        when(feedEngagementService.getComment("comment-1"))
                .thenReturn(FeedPostCommentResponseDTO.builder().id("comment-1").content("old").build());
        when(feedEngagementService.updateComment(any(), any()))
                .thenReturn(FeedPostCommentResponseDTO.builder().id("comment-1").content("new").build());

        mockMvc.perform(get("/api/v1/feed/comments/comment-1").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("old"));
        mockMvc.perform(put("/api/v1/feed/comments/comment-1")
                        .contentType("application/json")
                        .content("{\"content\":\"new\"}").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("new"));
    }

    @Test
    void nonAdminCannotAccessModerationQueue() throws Exception {
        mockMvc.perform(get("/api/v1/admin/feed/pending").with(artisan()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessModerationQueue() throws Exception {
        when(feedPostService.listPending(any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/feed/pending").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanPublishHideAndRemovePosts() throws Exception {
        when(feedPostService.publish(any(), any())).thenReturn(FeedPostResponseDTO.builder().id("post-1").build());
        when(feedPostService.hide(any(), any())).thenReturn(FeedPostResponseDTO.builder().id("post-1").build());

        mockMvc.perform(post("/api/v1/admin/feed/post-1/publish")
                        .contentType("application/json").content("{\"note\":\"approved\"}").with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/feed/post-1/hide")
                        .contentType("application/json").content("{\"note\":\"needs review\"}").with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/feed/post-1/remove").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void getLikesReturnsPaginatedLikers() throws Exception {
        FeedPostLikerDTO liker = FeedPostLikerDTO.builder().userId("user-1").name("User One").build();
        PaginatedResponse<FeedPostLikerDTO> response = PaginatedResponse.<FeedPostLikerDTO>builder()
                .content(List.of(liker))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();
        when(feedEngagementService.likers(eq("post-1"), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/feed/post-1/likes").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value("user-1"))
                .andExpect(jsonPath("$.data.content[0].name").value("User One"));
    }

    @Test
    void getLikeStatusReturnsLikeStatus() throws Exception {
        FeedPostLikeStatusDTO statusDto = new FeedPostLikeStatusDTO(5, true);
        when(feedEngagementService.likeStatus("post-1")).thenReturn(statusDto);

        mockMvc.perform(get("/api/v1/feed/post-1/likes/status").with(artisan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(5))
                .andExpect(jsonPath("$.data.likedByCurrentUser").value(true));
    }
}
