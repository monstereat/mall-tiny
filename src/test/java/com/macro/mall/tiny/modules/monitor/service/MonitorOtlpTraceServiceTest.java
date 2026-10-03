package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.dto.OtlpTraceExportRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class MonitorOtlpTraceServiceTest {
    private final MonitorProjectService projects = mock(MonitorProjectService.class);
    private final MonitorIngestService ingest = mock(MonitorIngestService.class);
    private final MonitorOtlpTraceService service = new MonitorOtlpTraceService(projects, ingest);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsOtlpJsonSpanAndRoutesItThroughAuthenticatedBatchIngest() throws Exception {
        String body = """
                {"resourceSpans":[{"resource":{"attributes":[
                  {"key":"service.name","value":{"stringValue":"checkout"}},
                  {"key":"service.version","value":{"stringValue":"1.2.3"}},
                  {"key":"deployment.environment.name","value":{"stringValue":"staging"}},
                  {"key":"user.secret","value":{"stringValue":"must-not-persist"}}]},
                  "scopeSpans":[{"spans":[{"traceId":"0123456789abcdef0123456789abcdef",
                    "spanId":"0123456789abcdef","parentSpanId":"fedcba9876543210","name":"GET /orders",
                    "kind":2,"startTimeUnixNano":"1780000000123456789","endTimeUnixNano":"1780000001123456789",
                    "attributes":[{"key":"http.response.status_code","value":{"intValue":"503"}},
                      {"key":"user.secret","value":{"stringValue":"must-not-persist"}}],
                    "status":{"code":2,"message":"failed"}}]}]}]}
                """;
        OtlpTraceExportRequest request = objectMapper.readValue(body, OtlpTraceExportRequest.class);
        when(projects.validateIngestKey("shop", "valid-key")).thenReturn(new MonitorProject());

        service.export("shop", "valid-key", request);

        verify(projects).validateIngestKey("shop", "valid-key");
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(ingest).ingestBatch(eq("valid-key"), captor.capture());
        assertEquals(1, captor.getValue().size());
        MonitorEventEnvelope event = (MonitorEventEnvelope) captor.getValue().get(0);
        assertEquals("shop", event.getProjectId());
        assertEquals("0123456789abcdef0123456789abcdef", event.getTraceId());
        assertEquals("1.2.3", event.getRelease());
        assertEquals("staging", event.getEnvironment());
        assertEquals(1_780_000_000_123L, event.getTimestamp());
        assertEquals("otel.server", event.getData().get("op"));
        assertEquals("GET /orders", event.getData().get("description"));
        assertEquals(1_000d, event.getData().get("durationMs"));
        assertEquals("error", event.getData().get("status"));
        assertEquals(503, event.getData().get("statusCode"));
        assertEquals("checkout", event.getData().get("serviceName"));
        assertFalse(event.getData().containsKey("user.secret"));
    }

    @Test
    void rejectsInvalidTimeRangeBeforeIngest() throws Exception {
        String body = """
                {"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"0123456789abcdef0123456789abcdef",
                  "spanId":"0123456789abcdef","name":"operation","kind":1,
                  "startTimeUnixNano":"2000000","endTimeUnixNano":"1000000"}]}]}]}
                """;
        OtlpTraceExportRequest request = objectMapper.readValue(body, OtlpTraceExportRequest.class);
        when(projects.validateIngestKey("shop", "key")).thenReturn(new MonitorProject());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.export("shop", "key", request));

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(ingest);
    }

    @Test
    void authenticatesEmptyRequestBeforeReturningSuccess() throws Exception {
        OtlpTraceExportRequest request = objectMapper.readValue("{\"resourceSpans\":[]}", OtlpTraceExportRequest.class);
        when(projects.validateIngestKey("shop", "bad")).thenThrow(
                new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThrows(ResponseStatusException.class, () -> service.export("shop", "bad", request));
        verify(projects).validateIngestKey("shop", "bad");
        verifyNoInteractions(ingest);
    }

    @Test
    void acceptsAuthenticatedEmptyJsonExportRequest() throws Exception {
        OtlpTraceExportRequest request = objectMapper.readValue("{}", OtlpTraceExportRequest.class);
        when(projects.validateIngestKey("shop", "valid-key")).thenReturn(new MonitorProject());

        assertDoesNotThrow(() -> service.export("shop", "valid-key", request));

        verify(projects).validateIngestKey("shop", "valid-key");
        verifyNoInteractions(ingest);
    }

    @Test
    void convertsStandardProtobufRequestAndDropsArbitraryAttributes() {
        Span span = Span.newBuilder()
                .setTraceId(ByteString.copyFrom(java.util.HexFormat.of().parseHex("0123456789abcdef0123456789abcdef")))
                .setSpanId(ByteString.copyFrom(java.util.HexFormat.of().parseHex("0123456789abcdef")))
                .setName("GET /protobuf")
                .setKind(Span.SpanKind.SPAN_KIND_SERVER)
                .setStartTimeUnixNano(1_780_000_000_123_000_000L)
                .setEndTimeUnixNano(1_780_000_000_124_000_000L)
                .addAttributes(KeyValue.newBuilder().setKey("http.response.status_code")
                        .setValue(AnyValue.newBuilder().setIntValue(201)))
                .addAttributes(KeyValue.newBuilder().setKey("sensitive.attribute")
                        .setValue(AnyValue.newBuilder().setStringValue("discard-me")))
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK))
                .build();
        ResourceSpans resourceSpans = ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(KeyValue.newBuilder().setKey("service.name")
                        .setValue(AnyValue.newBuilder().setStringValue("orders"))))
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(span))
                .build();
        byte[] payload = ExportTraceServiceRequest.newBuilder().addResourceSpans(resourceSpans).build().toByteArray();
        when(projects.validateIngestKey("shop", "valid-key")).thenReturn(new MonitorProject());

        service.exportProtobuf("shop", "valid-key", payload);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(ingest).ingestBatch(eq("valid-key"), captor.capture());
        MonitorEventEnvelope event = (MonitorEventEnvelope) captor.getValue().get(0);
        assertEquals("0123456789abcdef0123456789abcdef", event.getTraceId());
        assertEquals("orders", event.getData().get("serviceName"));
        assertEquals("otel.server", event.getData().get("op"));
        assertEquals(201, event.getData().get("statusCode"));
        assertFalse(event.getData().containsKey("sensitive.attribute"));
    }

    @Test
    void rejectsMalformedProtobufAfterProjectKeyAuthentication() {
        when(projects.validateIngestKey("shop", "key")).thenReturn(new MonitorProject());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.exportProtobuf("shop", "key", new byte[]{(byte) 0xff}));

        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(ingest);
    }
}
