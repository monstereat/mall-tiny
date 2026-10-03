package com.macro.mall.tiny.modules.monitor.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/** OTLP/HTTP JSON ExportTraceServiceRequest fields consumed by this server. */
@Data
public class OtlpTraceExportRequest {
    private List<ResourceSpans> resourceSpans;

    @Data
    public static class ResourceSpans {
        private Resource resource;
        private List<ScopeSpans> scopeSpans;
    }

    @Data
    public static class Resource {
        private List<Attribute> attributes;
    }

    @Data
    public static class ScopeSpans {
        private JsonNode scope;
        private List<Span> spans;
    }

    @Data
    public static class Span {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String name;
        private JsonNode kind;
        private String startTimeUnixNano;
        private String endTimeUnixNano;
        private List<Attribute> attributes;
        private Status status;
    }

    @Data
    public static class Status {
        private JsonNode code;
        private String message;
    }

    @Data
    public static class Attribute {
        private String key;
        private JsonNode value;
    }
}
