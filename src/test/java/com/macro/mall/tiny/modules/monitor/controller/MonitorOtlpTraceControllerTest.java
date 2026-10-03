package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.OtlpTraceExportRequest;
import com.macro.mall.tiny.modules.monitor.service.MonitorOtlpTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitorOtlpTraceControllerTest {
    private final MonitorOtlpTraceService service = mock(MonitorOtlpTraceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new MonitorOtlpTraceController(service, new ObjectMapper())).build();

    @Test
    void acceptsJsonAtProjectScopedOtlpPathAndReturnsEmptyOtlpResponse() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"resourceSpans\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"));

        verify(service).export(org.mockito.ArgumentMatchers.eq("shop"),
                org.mockito.ArgumentMatchers.eq("ingest-key"), org.mockito.ArgumentMatchers.any(OtlpTraceExportRequest.class));
    }

    @Test
    void rejectsNonJsonEncoding() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void acceptsProtobufAndReturnsEmptyProtobufSuccessResponse() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .contentType("application/x-protobuf")
                        .accept("application/x-protobuf")
                        .content(new byte[0]))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-protobuf"))
                .andExpect(content().bytes(new byte[0]));

        verify(service).exportProtobuf(org.mockito.ArgumentMatchers.eq("shop"),
                org.mockito.ArgumentMatchers.eq("ingest-key"), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void rejectsOversizedDeclaredBodyBeforeReadingIt() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn((long) MonitorOtlpTraceController.MAX_REQUEST_BYTES + 1);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new MonitorOtlpTraceController(service, new ObjectMapper())
                        .exportTraces("shop", "key", request));

        assertEquals(413, error.getStatusCode().value());
    }

    @Test
    void rejectsInvalidProjectKeyBeforeReadingRequestStream() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getHeader("Content-Encoding")).thenReturn(null);
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED))
                .when(service).validateIngestKey("shop", "invalid-key");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new MonitorOtlpTraceController(service, new ObjectMapper())
                        .exportTraces("shop", "invalid-key", request));

        assertEquals(401, error.getStatusCode().value());
        verify(request, never()).getInputStream();
        verify(service).validateIngestKey("shop", "invalid-key");
    }

    @Test
    void acceptsGzipEncodedJsonRequest() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gzip("{\"resourceSpans\":[]}".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        verify(service).export(org.mockito.ArgumentMatchers.eq("shop"),
                org.mockito.ArgumentMatchers.eq("ingest-key"), org.mockito.ArgumentMatchers.any(OtlpTraceExportRequest.class));
    }

    @Test
    void acceptsGzipEncodedProtobufRequestAndKeepsOtlpResponseEncoding() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .header("Content-Encoding", "gzip")
                        .contentType("application/x-protobuf")
                        .accept("application/x-protobuf")
                        .content(gzip(new byte[0])))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-protobuf"))
                .andExpect(content().bytes(new byte[0]));

        verify(service).exportProtobuf(org.mockito.ArgumentMatchers.eq("shop"),
                org.mockito.ArgumentMatchers.eq("ingest-key"), org.mockito.ArgumentMatchers.any(byte[].class));
    }

    @Test
    void rejectsMalformedGzipBody() throws Exception {
        mvc.perform(post("/api/v1/otlp/shop/v1/traces")
                        .header("X-Monitor-Key", "ingest-key")
                        .header("Content-Encoding", "gzip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not gzip"))
                .andExpect(status().isBadRequest());

        verify(service, never()).export(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enforcesCompressedAndExpandedBodyLimits() throws IOException {
        ResponseStatusException expanded = assertThrows(ResponseStatusException.class,
                () -> MonitorOtlpTraceController.readBoundedRequestBody(
                        new ByteArrayInputStream(gzip(new byte[100])), -1, "gzip", 32));
        assertEquals(413, expanded.getStatusCode().value());

        ResponseStatusException compressed = assertThrows(ResponseStatusException.class,
                () -> MonitorOtlpTraceController.readBoundedRequestBody(
                        new ByteArrayInputStream(gzip(new byte[0])), -1, "gzip", 10));
        assertEquals(413, compressed.getStatusCode().value());
    }

    @Test
    void rejectsChunkedBodyAsSoonAsItCrossesTheConfiguredBound() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> MonitorOtlpTraceController.readBoundedBody(new ByteArrayInputStream(new byte[5]), -1, 4));

        assertEquals(413, error.getStatusCode().value());
    }

    private byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body);
        }
        return compressed.toByteArray();
    }
}
