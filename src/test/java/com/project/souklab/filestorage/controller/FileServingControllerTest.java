package com.project.souklab.filestorage.controller;

import com.project.souklab.controller.support.ControllerSliceTest;
import com.project.souklab.filestorage.StorageResource;
import com.project.souklab.filestorage.StorageService;
import com.project.souklab.service.storage.FileAccessService;
import com.project.souklab.filestorage.exception.FileNotFoundStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.project.souklab.controller.support.SecurityTestUtils.artisan;
import static com.project.souklab.controller.support.SecurityTestUtils.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;

/**
 * Controller slice tests and stream lifecycle verification for FileServingController.
 * Verifies GET /api/v1/files/{key} routing, authentication enforcement, response headers,
 * streaming dispatch, error handling, and stream closure guarantees.
 */
@ControllerSliceTest(controllers = FileServingController.class)
@Import(FileServingAsyncTestConfig.class)
class FileServingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private FileAccessService fileAccessService;

    @Nested
    @DisplayName("GET /api/v1/files/{key} - Authentication & Authorization")
    class SecurityTests {

        /**
         * Verifies that unauthenticated requests are uniformly rejected with 401 Unauthorized by Spring Security.
         */
        @Test
        @DisplayName("Unauthenticated request is rejected with 401 Unauthorized")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/files/{key}", "sample-uuid.jpg"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        /**
         * Verifies that authenticated client user can access file serving endpoint.
         */
        @Test
        @DisplayName("Authenticated client request successfully accesses file endpoint")
        void authenticatedClient_canAccess() throws Exception {
            byte[] content = "test image data".getBytes(StandardCharsets.UTF_8);
            StorageResource resource = new StorageResource(
                    "sample-uuid.jpg",
                    new ByteArrayInputStream(content),
                    "image/jpeg",
                    content.length,
                    "profile.jpg"
            );
            when(storageService.retrieve("sample-uuid.jpg")).thenReturn(resource);

            MvcResult result = mockMvc.perform(get("/api/v1/files/{key}", "sample-uuid.jpg").with(client()))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(content));
        }

        /**
         * Verifies that authenticated artisan user can access file serving endpoint.
         */
        @Test
        @DisplayName("Authenticated artisan request successfully accesses file endpoint")
        void authenticatedArtisan_canAccess() throws Exception {
            byte[] content = "artisan image data".getBytes(StandardCharsets.UTF_8);
            StorageResource resource = new StorageResource(
                    "artisan-uuid.png",
                    new ByteArrayInputStream(content),
                    "image/png",
                    content.length,
                    "workshop.png"
            );
            when(storageService.retrieve("artisan-uuid.png")).thenReturn(resource);

            MvcResult result = mockMvc.perform(get("/api/v1/files/{key}", "artisan-uuid.png").with(artisan()))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(content));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/files/{key} - Response Headers & Streaming")
    class ResponseHeaderTests {

        /**
         * Verifies that a successful retrieval sets Content-Type, Content-Length, inline Content-Disposition,
         * immutable Cache-Control headers, and streams the exact payload bytes.
         */
        @Test
        @DisplayName("Success returns 200 with complete response headers and streamed content")
        void success_returns200WithHeadersAndContent() throws Exception {
            byte[] content = "sample file content bytes".getBytes(StandardCharsets.UTF_8);
            StorageResource resource = new StorageResource(
                    "my-key-123.jpg",
                    new ByteArrayInputStream(content),
                    "image/jpeg",
                    content.length,
                    "avatar_original.jpg"
            );
            when(storageService.retrieve("my-key-123.jpg")).thenReturn(resource);

            MvcResult result = mockMvc.perform(get("/api/v1/files/{key}", "my-key-123.jpg").with(client()))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                    .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.length)))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline; filename=\"avatar_original.jpg\"")))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable"))
                    .andExpect(content().bytes(content));
        }

        /**
         * Verifies that when contentType is null or unparseable, fallback application/octet-stream is used.
         */
        @Test
        @DisplayName("Null content type falls back to application/octet-stream")
        void nullContentType_fallsBackToOctetStream() throws Exception {
            byte[] content = "binary data".getBytes(StandardCharsets.UTF_8);
            StorageResource resource = new StorageResource(
                    "binary-file.bin",
                    new ByteArrayInputStream(content),
                    null,
                    content.length,
                    "binary-file.bin"
            );
            when(storageService.retrieve("binary-file.bin")).thenReturn(resource);

            MvcResult result = mockMvc.perform(get("/api/v1/files/{key}", "binary-file.bin").with(client()))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
        }

        /**
         * Verifies that when originalFilename is null, the storage key is used as filename in Content-Disposition.
         */
        @Test
        @DisplayName("Null original filename falls back to storage key in Content-Disposition")
        void nullOriginalFilename_fallsBackToKey() throws Exception {
            byte[] content = "file data".getBytes(StandardCharsets.UTF_8);
            StorageResource resource = new StorageResource(
                    "uuid-key-without-name.png",
                    new ByteArrayInputStream(content),
                    "image/png",
                    content.length,
                    null
            );
            when(storageService.retrieve("uuid-key-without-name.png")).thenReturn(resource);

            MvcResult result = mockMvc.perform(get("/api/v1/files/{key}", "uuid-key-without-name.png").with(client()))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline; filename=\"uuid-key-without-name.png\"")));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/files/{key} - Error Paths")
    class ErrorPathTests {

        /**
         * Verifies that FileNotFoundStorageException maps to 404 Not Found with standard ApiResponse error envelope.
         */
        @Test
        @DisplayName("FileNotFoundStorageException returns 404 with FILE_NOT_FOUND error code")
        void fileNotFound_returns404() throws Exception {
            when(storageService.retrieve("nonexistent-key.jpg"))
                    .thenThrow(new FileNotFoundStorageException("nonexistent-key.jpg"));

            mockMvc.perform(get("/api/v1/files/{key}", "nonexistent-key.jpg").with(client()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.errorCode").value("FILE_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("File not found for key: nonexistent-key.jpg"));
        }
    }

    @Nested
    @DisplayName("Stream Lifecycle & Connection Closure")
    class StreamClosureTests {

        /**
         * Verifies that the underlying storage InputStream is closed when StreamingResponseBody completes successfully.
         */
        @Test
        @DisplayName("Underlying storage InputStream is closed upon successful stream completion")
        void inputStreamClosed_onSuccessfulStream() throws IOException {
            AtomicBoolean closed = new AtomicBoolean(false);
            byte[] data = "stream payload".getBytes(StandardCharsets.UTF_8);
            InputStream spyStream = new ByteArrayInputStream(data) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            StorageResource resource = new StorageResource("test-key", spyStream, "text/plain", data.length, "test.txt");
            StorageService mockService = mock(StorageService.class);
            when(mockService.retrieve("test-key")).thenReturn(resource);
            FileServingController controller = new FileServingController(mockService, mock(FileAccessService.class));

            ResponseEntity<StreamingResponseBody> response = controller.serveFile("test-key");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThat(response.getBody()).isNotNull();
            response.getBody().writeTo(output);

            assertThat(output.toByteArray()).isEqualTo(data);
            assertThat(closed.get()).isTrue();
        }

        /**
         * Verifies that the underlying storage InputStream is closed even if writing to the output stream fails with an IOException.
         */
        @Test
        @DisplayName("Underlying storage InputStream is closed even when writing encounters an IOException")
        void inputStreamClosed_onWriteException() {
            AtomicBoolean closed = new AtomicBoolean(false);
            byte[] data = "stream payload".getBytes(StandardCharsets.UTF_8);
            InputStream spyStream = new ByteArrayInputStream(data) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            StorageResource resource = new StorageResource("test-key", spyStream, "text/plain", data.length, "test.txt");
            StorageService mockService = mock(StorageService.class);
            when(mockService.retrieve("test-key")).thenReturn(resource);
            FileServingController controller = new FileServingController(mockService, mock(FileAccessService.class));

            ResponseEntity<StreamingResponseBody> response = controller.serveFile("test-key");
            OutputStream throwingOutput = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Simulated network pipe break");
                }
            };

            assertThat(response.getBody()).isNotNull();
            assertThatThrownBy(() -> response.getBody().writeTo(throwingOutput))
                    .isInstanceOf(IOException.class)
                    .hasMessage("Simulated network pipe break");

            assertThat(closed.get()).isTrue();
        }
    }

    @Test
    void directControllerHandlesInvalidMediaTypeBlankFilenameNullStreamAndPublicCache() throws Exception {
        StorageService storage = mock(StorageService.class);
        FileAccessService access = mock(FileAccessService.class);
        when(access.authorize("public-key")).thenReturn(true);
        StorageResource resource = new StorageResource("public-key", null, "not/a valid type", 0, " ");
        when(storage.retrieve("public-key")).thenReturn(resource);
        FileServingController controller = new FileServingController(storage, access);

        ResponseEntity<StreamingResponseBody> response = controller.serveFile("public-key");
        assertThat(response.getHeaders().getCacheControl()).contains("public");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("public-key");
        response.getBody().writeTo(new ByteArrayOutputStream());
    }
}
