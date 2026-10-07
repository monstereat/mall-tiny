package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client for Loki's asynchronous log deletion API. */
@Component
public class MonitorLokiDeletionClient {

    private static final Pattern PROGRESS_PATTERN = Pattern.compile("^(\\d{1,3})%\\s+Complete$");
    private final RestClient loki;

    @Autowired
    public MonitorLokiDeletionClient(
            @Value("${monitor.loki.url:http://loki:3100}") String lokiUrl) {
        this(RestClient.builder().baseUrl(lokiUrl).build());
    }

    MonitorLokiDeletionClient(RestClient loki) {
        this.loki = loki;
    }

    /**
     * Submits a delete request. A successful response means the request was accepted, not completed.
     */
    public void submitDelete(String logQl, Instant start, Instant end) {
        if (!StringUtils.hasText(logQl) || start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("query and a valid start/end range are required");
        }
        loki.post()
                .uri(uri -> uri.path("/loki/api/v1/delete")
                        .queryParam("query", "{query}")
                        .queryParam("start", "{start}")
                        .queryParam("end", "{end}")
                        .build(logQl, start.toString(), end.toString()))
                .retrieve()
                .toBodilessEntity();
    }

    /** Lists Loki's persisted delete requests. The list is tenant-scoped by Loki. */
    public List<MonitorLokiDeleteRequest> listRequests() {
        JsonNode response = loki.get()
                .uri("/loki/api/v1/delete")
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.isNull()) {
            return List.of();
        }

        JsonNode requests = response.isArray() ? response : response.path("data");
        if (!requests.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Loki delete request list response is invalid");
        }
        List<MonitorLokiDeleteRequest> result = new ArrayList<>(requests.size());
        for (JsonNode request : requests) {
            String requestId = text(request, "request_id");
            String query = text(request, "query");
            Instant start = instant(first(request, "start_time", "start"));
            Instant end = instant(first(request, "end_time", "end"));
            Instant createdAt = instant(request.path("created_at"));
            String status = text(request, "status");
            Integer progress = progress(request.path("progress"), status);
            result.add(new MonitorLokiDeleteRequest(requestId, query, start, end, createdAt, status, progress));
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private JsonNode first(JsonNode node, String primary, String alternate) {
        JsonNode value = node.path(primary);
        return value.isMissingNode() || value.isNull() ? node.path(alternate) : value;
    }

    private Instant instant(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        try {
            if (value.isNumber()) {
                BigDecimal number = value.decimalValue();
                if (number.abs().compareTo(BigDecimal.valueOf(1_000_000_000_000L)) >= 0) {
                    return Instant.ofEpochMilli(number.longValue());
                }
                BigDecimal nanos = number.movePointRight(9);
                long seconds = nanos.longValue() / 1_000_000_000L;
                long nanoAdjustment = nanos.longValue() % 1_000_000_000L;
                return Instant.ofEpochSecond(seconds, nanoAdjustment);
            }
            String timestamp = value.asText();
            if (!StringUtils.hasText(timestamp)) return null;
            try {
                return Instant.parse(timestamp);
            } catch (DateTimeParseException ignored) {
                return Instant.ofEpochMilli(new BigDecimal(timestamp).longValue());
            }
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Loki delete request list contains an invalid timestamp", e);
        }
    }

    private Integer progress(JsonNode value, String status) {
        if (value != null && value.isNumber()) {
            return Math.max(0, Math.min(100, value.asInt()));
        }
        if ("received".equalsIgnoreCase(status)) return 0;
        if ("processed".equalsIgnoreCase(status)) return 100;
        if (status != null) {
            Matcher matcher = PROGRESS_PATTERN.matcher(status.trim());
            if (matcher.matches()) return Math.max(0, Math.min(100, Integer.parseInt(matcher.group(1))));
        }
        return null;
    }
}
