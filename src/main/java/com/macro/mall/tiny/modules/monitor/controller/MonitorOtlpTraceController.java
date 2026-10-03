package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.OtlpTraceExportRequest;
import com.macro.mall.tiny.modules.monitor.dto.OtlpTraceExportResponse;
import com.macro.mall.tiny.modules.monitor.service.MonitorOtlpTraceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/api/v1/otlp/{projectKey}/v1")
@RequiredArgsConstructor
public class MonitorOtlpTraceController {
    static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;

    private final MonitorOtlpTraceService otlpTraceService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/traces", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OtlpTraceExportResponse> exportTraces(
            @PathVariable String projectKey,
            @RequestHeader("X-Monitor-Key") String ingestKey,
            HttpServletRequest servletRequest) {
        otlpTraceService.validateIngestKey(projectKey, ingestKey);
        OtlpTraceExportRequest request;
        try {
            request = objectMapper.readValue(readBoundedRequestBody(servletRequest), OtlpTraceExportRequest.class);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTLP JSON request is malformed", e);
        }
        otlpTraceService.export(projectKey, ingestKey, request);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(new OtlpTraceExportResponse());
    }

    @PostMapping(value = "/traces", consumes = "application/x-protobuf",
            produces = "application/x-protobuf")
    public ResponseEntity<byte[]> exportTracesProtobuf(
            @PathVariable String projectKey,
            @RequestHeader("X-Monitor-Key") String ingestKey,
            HttpServletRequest servletRequest) {
        otlpTraceService.validateIngestKey(projectKey, ingestKey);
        otlpTraceService.exportProtobuf(projectKey, ingestKey, readBoundedRequestBody(servletRequest));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "application/x-protobuf").body(new byte[0]);
    }

    private byte[] readBoundedRequestBody(HttpServletRequest request) {
        try {
            return readBoundedRequestBody(request.getInputStream(), request.getContentLengthLong(),
                    request.getHeader("Content-Encoding"), MAX_REQUEST_BYTES);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTLP request body could not be read", e);
        }
    }

    static byte[] readBoundedRequestBody(InputStream input, long contentLength, String contentEncoding,
                                         int maxBytes) throws IOException {
        if (contentLength > maxBytes) {
            throw payloadTooLarge();
        }
        if (!StringUtils.hasText(contentEncoding) || "identity".equalsIgnoreCase(contentEncoding.trim())) {
            return readBoundedBody(input, contentLength, maxBytes);
        }
        if (!"gzip".equalsIgnoreCase(contentEncoding.trim())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "OTLP request Content-Encoding is unsupported");
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new LimitedInputStream(input, maxBytes))) {
            return readBoundedBody(gzip, -1, maxBytes);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTLP gzip request body is malformed", e);
        }
    }

    static byte[] readBoundedBody(InputStream input, long contentLength, int maxBytes) throws IOException {
        if (contentLength > maxBytes) {
            throw payloadTooLarge();
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) {
                throw payloadTooLarge();
            }
            body.write(buffer, 0, read);
            total += read;
        }
        return body.toByteArray();
    }

    private static ResponseStatusException payloadTooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP request exceeds 64 MiB");
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final int maxBytes;
        private int total;

        private LimitedInputStream(InputStream input, int maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++total > maxBytes) {
                throw payloadTooLarge();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int allowed = Math.min(length, maxBytes - total + 1);
            int read = super.read(buffer, offset, allowed);
            if (read > 0) {
                total += read;
                if (total > maxBytes) {
                    throw payloadTooLarge();
                }
            }
            return read;
        }
    }
}
