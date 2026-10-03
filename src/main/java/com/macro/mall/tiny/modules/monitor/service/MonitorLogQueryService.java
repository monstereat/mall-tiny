package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitorLogQueryService {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private final JdbcTemplate clickHouse;
    private final RestClient loki;

    public MonitorLogQueryService(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse,
            @Value("${monitor.loki.url:http://loki:3100}") String lokiUrl) {
        this.clickHouse = clickHouse;
        this.loki = RestClient.builder().baseUrl(lokiUrl).build();
    }

    public MonitorLogSearchResult search(MonitorProject project, String traceId, int hours, int limit) {
        return search(project, traceId, hours, limit, null);
    }

    public MonitorLogSearchResult search(
            MonitorProject project, String traceId, int hours, int limit, String containsText) {
        String normalizedTraceId = traceId;
        if (StringUtils.hasText(traceId)) {
            if (!traceId.matches("(?i)[0-9a-f]{32}") || traceId.matches("0{32}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId must be a non-zero W3C trace ID");
            }
            normalizedTraceId = traceId.toLowerCase();
        }
        if (normalizedTraceId != null && !belongsToProject(project.getProjectKey(), normalizedTraceId)) {
            return new MonitorLogSearchResult(normalizedTraceId, List.of());
        }

        Instant end = Instant.now();
        Instant start = end.minusSeconds(Math.max(1, Math.min(168, hours)) * 3600L);
        String query = "{service_name=\"observability-platform\"} | monitor_project=" +
                logqlQuote(project.getProjectKey());
        if (normalizedTraceId != null) {
            query += " | monitor_trace_id=" + logqlQuote(normalizedTraceId);
        }
        if (StringUtils.hasText(containsText)) {
            query += " |= " + logqlQuote(containsText);
        }
        String lokiQuery = query;
        try {
            JsonNode response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", toNanos(start))
                            .queryParam("end", toNanos(end))
                            .queryParam("direction", "backward")
                            .queryParam("limit", Math.max(1, Math.min(500, limit)))
                            .build(Map.of("query", lokiQuery)))
                    .retrieve()
                    .body(JsonNode.class);
            return new MonitorLogSearchResult(normalizedTraceId, entries(response));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage query failed", e);
        }
    }

    public BigDecimal countErrors(MonitorProject project, int windowSeconds) {
        int window = Math.max(60, Math.min(86_400, windowSeconds));
        String query = "sum(count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                logqlQuote(project.getProjectKey()) + " | severity_text=\"ERROR\" [" + window + "s]))";
        try {
            JsonNode response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query")
                            .queryParam("query", "{query}")
                            .build(Map.of("query", query)))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode value = response == null ? null : response.path("data").path("result").path(0).path("value").path(1);
            if (value == null || value.isMissingNode() || !StringUtils.hasText(value.asText())) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(value.asText());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage query failed", e);
        }
    }

    private boolean belongsToProject(String projectKey, String traceId) {
        Long found = clickHouse.queryForObject(
                "SELECT count() FROM (" +
                        "SELECT event_id FROM monitor.error_event WHERE project_id=? AND trace_id=? " +
                        "UNION ALL SELECT event_id FROM monitor.performance_event WHERE project_id=? AND trace_id=? " +
                        "UNION ALL SELECT event_id FROM monitor.behavior_event WHERE project_id=? AND trace_id=? " +
                        "UNION ALL SELECT event_id FROM monitor.replay_event WHERE project_id=? AND trace_id=? " +
                        "UNION ALL SELECT event_id FROM monitor.metric_event WHERE project_id=? AND trace_id=? " +
                        "LIMIT 1)",
                Long.class,
                projectKey, traceId, projectKey, traceId, projectKey, traceId, projectKey, traceId, projectKey, traceId
        );
        return found != null && found > 0;
    }

    private List<MonitorLogSearchResult.Entry> entries(JsonNode response) {
        List<MonitorLogSearchResult.Entry> entries = new ArrayList<>();
        JsonNode result = response == null ? null : response.path("data").path("result");
        if (result == null || !result.isArray()) {
            return entries;
        }
        for (JsonNode stream : result) {
            Map<String, String> labels = stringMap(stream.path("stream"));
            JsonNode values = stream.path("values");
            if (!values.isArray()) continue;
            for (JsonNode value : values) {
                if (!value.isArray() || value.size() < 2) continue;
                Map<String, String> metadata = value.size() > 2 ? stringMap(value.get(2)) : Map.of();
                entries.add(new MonitorLogSearchResult.Entry(
                        timestamp(value.get(0).asText()),
                        value.get(1).asText(),
                        labels,
                        metadata
                ));
            }
        }
        return entries;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        }
        return result;
    }

    private String toNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(instant.getNano())).toString();
    }

    private String logqlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private String timestamp(String nanos) {
        try {
            BigInteger[] parts = new BigInteger(nanos).divideAndRemainder(NANOS_PER_SECOND);
            return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValueExact()).toString();
        } catch (RuntimeException e) {
            return nanos;
        }
    }
}
