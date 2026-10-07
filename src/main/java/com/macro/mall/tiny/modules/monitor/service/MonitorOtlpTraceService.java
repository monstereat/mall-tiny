package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.dto.OtlpTraceExportRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class MonitorOtlpTraceService {
    private static final int MAX_SPANS_PER_REQUEST = 5_000;
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);

    private final MonitorProjectService projectService;
    private final MonitorIngestService ingestService;

    public void validateIngestKey(String projectKey, String ingestKey) {
        projectService.validateIngestKey(projectKey, ingestKey);
    }

    public void export(String projectKey, String ingestKey, OtlpTraceExportRequest request) {
        projectService.validateIngestKey(projectKey, ingestKey);
        acceptConverted(projectKey, ingestKey, convert(projectKey, request));
    }

    public void exportProtobuf(String projectKey, String ingestKey, byte[] payload) {
        projectService.validateIngestKey(projectKey, ingestKey);
        try {
            ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(payload);
            acceptConverted(projectKey, ingestKey, convert(projectKey, toJsonDto(request)));
        } catch (InvalidProtocolBufferException e) {
            throw badRequest("OTLP protobuf request is malformed");
        }
    }

    private void acceptConverted(String projectKey, String ingestKey, List<MonitorEventEnvelope> events) {
        if (events.isEmpty()) return;
        ingestService.ingestBatch(ingestKey, events);
    }

    private OtlpTraceExportRequest toJsonDto(ExportTraceServiceRequest request) {
        OtlpTraceExportRequest dto = new OtlpTraceExportRequest();
        List<OtlpTraceExportRequest.ResourceSpans> resourceSpans = new ArrayList<>();
        for (io.opentelemetry.proto.trace.v1.ResourceSpans sourceResourceSpans : request.getResourceSpansList()) {
            OtlpTraceExportRequest.ResourceSpans resourceSpansDto = new OtlpTraceExportRequest.ResourceSpans();
            OtlpTraceExportRequest.Resource resource = new OtlpTraceExportRequest.Resource();
            resource.setAttributes(toAttributes(sourceResourceSpans.hasResource()
                    ? sourceResourceSpans.getResource().getAttributesList() : List.of()));
            resourceSpansDto.setResource(resource);
            List<OtlpTraceExportRequest.ScopeSpans> scopeSpans = new ArrayList<>();
            for (io.opentelemetry.proto.trace.v1.ScopeSpans sourceScopeSpans : sourceResourceSpans.getScopeSpansList()) {
                OtlpTraceExportRequest.ScopeSpans scopeSpansDto = new OtlpTraceExportRequest.ScopeSpans();
                List<OtlpTraceExportRequest.Span> spans = new ArrayList<>();
                for (Span sourceSpan : sourceScopeSpans.getSpansList()) {
                    OtlpTraceExportRequest.Span span = new OtlpTraceExportRequest.Span();
                    span.setTraceId(HexFormat.of().formatHex(sourceSpan.getTraceId().toByteArray()));
                    span.setSpanId(HexFormat.of().formatHex(sourceSpan.getSpanId().toByteArray()));
                    span.setParentSpanId(HexFormat.of().formatHex(sourceSpan.getParentSpanId().toByteArray()));
                    span.setName(sourceSpan.getName());
                    span.setKind(JsonNodeFactory.instance.numberNode(sourceSpan.getKindValue()));
                    span.setStartTimeUnixNano(Long.toString(sourceSpan.getStartTimeUnixNano()));
                    span.setEndTimeUnixNano(Long.toString(sourceSpan.getEndTimeUnixNano()));
                    span.setAttributes(toAttributes(sourceSpan.getAttributesList()));
                    OtlpTraceExportRequest.Status status = new OtlpTraceExportRequest.Status();
                    status.setCode(JsonNodeFactory.instance.numberNode(sourceSpan.getStatus().getCodeValue()));
                    span.setStatus(status);
                    spans.add(span);
                }
                scopeSpansDto.setSpans(spans);
                scopeSpans.add(scopeSpansDto);
            }
            resourceSpansDto.setScopeSpans(scopeSpans);
            resourceSpans.add(resourceSpansDto);
        }
        dto.setResourceSpans(resourceSpans);
        return dto;
    }

    private List<OtlpTraceExportRequest.Attribute> toAttributes(List<KeyValue> attributes) {
        List<OtlpTraceExportRequest.Attribute> result = new ArrayList<>();
        for (KeyValue source : attributes) {
            OtlpTraceExportRequest.Attribute attribute = new OtlpTraceExportRequest.Attribute();
            attribute.setKey(source.getKey());
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            AnyValue any = source.getValue();
            switch (any.getValueCase()) {
                case STRING_VALUE -> value.put("stringValue", any.getStringValue());
                case INT_VALUE -> value.put("intValue", Long.toString(any.getIntValue()));
                default -> { }
            }
            attribute.setValue(value);
            result.add(attribute);
        }
        return result;
    }

    List<MonitorEventEnvelope> convert(String projectKey, OtlpTraceExportRequest request) {
        if (request == null) throw badRequest("OTLP request body is required");
        List<MonitorEventEnvelope> events = new ArrayList<>();
        for (OtlpTraceExportRequest.ResourceSpans resourceSpans :
                request.getResourceSpans() == null ? List.<OtlpTraceExportRequest.ResourceSpans>of() : request.getResourceSpans()) {
            if (resourceSpans == null) throw badRequest("resourceSpans entries must not be null");
            Map<String, String> resource = resourceAttributes(resourceSpans.getResource());
            for (OtlpTraceExportRequest.ScopeSpans scopeSpans :
                    resourceSpans.getScopeSpans() == null ? List.<OtlpTraceExportRequest.ScopeSpans>of() : resourceSpans.getScopeSpans()) {
                if (scopeSpans == null) throw badRequest("scopeSpans entries must not be null");
                for (OtlpTraceExportRequest.Span span :
                        scopeSpans.getSpans() == null ? List.<OtlpTraceExportRequest.Span>of() : scopeSpans.getSpans()) {
                    if (span == null) throw badRequest("span entries must not be null");
                    if (events.size() >= MAX_SPANS_PER_REQUEST) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP request exceeds 5000 spans");
                    }
                    events.add(toEvent(projectKey, resource, span));
                }
            }
        }
        return events;
    }

    private MonitorEventEnvelope toEvent(String projectKey, Map<String, String> resource,
                                         OtlpTraceExportRequest.Span span) {
        String traceId = span.getTraceId();
        String spanId = span.getSpanId();
        String name = span.getName();
        if (!isHexId(traceId, 32) || !isHexId(spanId, 16) || !StringUtils.hasText(name) || name.length() > 512) {
            throw badRequest("OTLP span must have valid traceId, spanId and a name up to 512 characters");
        }
        if (span.getParentSpanId() != null && !span.getParentSpanId().isEmpty()
                && !isHexId(span.getParentSpanId(), 16)) {
            throw badRequest("OTLP parentSpanId must be a 16-character hex ID");
        }
        long startNanos = parseUnixNanos(span.getStartTimeUnixNano(), "startTimeUnixNano");
        long endNanos = parseUnixNanos(span.getEndTimeUnixNano(), "endTimeUnixNano");
        if (endNanos < startNanos) throw badRequest("OTLP span endTimeUnixNano must not precede startTimeUnixNano");
        long startMillis = startNanos / NANOS_PER_MILLI.longValue();
        double durationMillis = BigDecimal.valueOf(endNanos - startNanos)
                .divide(BigDecimal.valueOf(1_000_000L), 6, java.math.RoundingMode.HALF_UP)
                .doubleValue();
        int kind = integerValue(span.getKind(), 0, 5, "kind", 0);
        int statusCode = integerValue(span.getStatus() == null ? null : span.getStatus().getCode(),
                0, 2, "status.code", 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spanId", spanId.toLowerCase(java.util.Locale.ROOT));
        if (StringUtils.hasText(span.getParentSpanId())) {
            data.put("parentSpanId", span.getParentSpanId().toLowerCase(java.util.Locale.ROOT));
        }
        data.put("op", "otel." + kindName(kind));
        data.put("description", name);
        data.put("startTime", startMillis);
        data.put("durationMs", durationMillis);
        data.put("status", statusCode == 2 ? "error" : "ok");
        data.put("statusCode", httpStatusCode(span.getAttributes()));
        String serviceName = resource.get("service.name");
        if (serviceName != null) data.put("serviceName", serviceName);

        MonitorEventEnvelope event = new MonitorEventEnvelope();
        event.setEventId(UUID.randomUUID().toString());
        event.setProjectId(projectKey);
        event.setEventType(MonitorEventType.SPAN);
        event.setTimestamp(startMillis);
        event.setTraceId(traceId.toLowerCase(java.util.Locale.ROOT));
        event.setRelease(first(resource, "service.version", "monitor.release"));
        event.setEnvironment(first(resource, "deployment.environment.name", "deployment.environment"));
        event.setData(data);
        return event;
    }

    private Map<String, String> resourceAttributes(OtlpTraceExportRequest.Resource resource) {
        Map<String, String> result = new LinkedHashMap<>();
        if (resource == null || resource.getAttributes() == null) return result;
        for (OtlpTraceExportRequest.Attribute attribute : resource.getAttributes()) {
            if (attribute == null || attribute.getKey() == null) continue;
            String key = attribute.getKey();
            if (!List.of("service.name", "service.version", "monitor.release",
                    "deployment.environment.name", "deployment.environment").contains(key)) continue;
            JsonNode value = attribute.getValue();
            JsonNode text = value == null ? null : value.get("stringValue");
            if (text != null && text.isTextual() && text.textValue().length() <= 128) {
                result.putIfAbsent(key, text.textValue());
            }
        }
        return result;
    }

    private Integer httpStatusCode(List<OtlpTraceExportRequest.Attribute> attributes) {
        if (attributes == null) return 0;
        for (OtlpTraceExportRequest.Attribute attribute : attributes) {
            if (attribute == null || !("http.response.status_code".equals(attribute.getKey())
                    || "http.status_code".equals(attribute.getKey()))) continue;
            JsonNode value = attribute.getValue();
            JsonNode number = value == null ? null : value.get("intValue");
            if (number == null) continue;
            try {
                int code;
                if (number.isIntegralNumber() && number.canConvertToInt()) code = number.intValue();
                else if (number.isTextual()) code = Integer.parseInt(number.textValue());
                else continue;
                if (code >= 0 && code <= 599) return code;
            } catch (NumberFormatException ignored) { /* Ignore out-of-range string values. */ }
        }
        return 0;
    }

    private long parseUnixNanos(String value, String field) {
        if (!StringUtils.hasText(value)) throw badRequest("OTLP span " + field + " is required");
        try {
            BigInteger nanos = new BigInteger(value);
            if (nanos.signum() < 0 || nanos.bitLength() > 63) throw new NumberFormatException();
            return nanos.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw badRequest("OTLP span " + field + " must be a non-negative 64-bit integer");
        }
    }

    private boolean isHexId(String value, int length) {
        return value != null && value.length() == length && value.matches("(?i)[0-9a-f]+")
                && !value.matches("(?i)0{" + length + "}");
    }

    private int integerValue(JsonNode value, int min, int max, String field, int defaultValue) {
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw badRequest("OTLP span " + field + " must be an integer from " + min + " to " + max);
        }
        return value.intValue();
    }

    private String kindName(int kind) {
        return switch (kind) {
            case 1 -> "internal";
            case 2 -> "server";
            case 3 -> "client";
            case 4 -> "producer";
            case 5 -> "consumer";
            default -> "unspecified";
        };
    }

    private String first(Map<String, String> values, String preferred, String fallback) {
        String value = values.get(preferred);
        return value == null ? values.get(fallback) : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
