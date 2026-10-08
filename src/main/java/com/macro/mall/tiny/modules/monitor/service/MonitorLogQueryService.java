package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreNumericBucket;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
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
    private static final String EXPLORE_NUMERIC_VALUE = "monitor_explore_numeric_value";
    private static final String EXPLORE_NUMERIC_VALUE_REGEX =
            "^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$";
    private static final String MAX_FINITE_DOUBLE = "1.7976931348623157e308";
    private static final int MAX_EXPLORE_NUMERIC_GROUPS = 1_000;
    private final JdbcTemplate clickHouse;
    private final RestClient loki;

    @Autowired
    public MonitorLogQueryService(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse,
            @Value("${monitor.loki.url:http://loki:3100}") String lokiUrl) {
        this.clickHouse = clickHouse;
        this.loki = RestClient.builder().baseUrl(lokiUrl).build();
    }

    MonitorLogQueryService(JdbcTemplate clickHouse, RestClient loki) {
        this.clickHouse = clickHouse;
        this.loki = loki;
    }

    public MonitorLogSearchResult search(MonitorProject project, String traceId, int hours, int limit) {
        return search(project, traceId, hours, limit, null);
    }

    public MonitorLogSearchResult search(
            MonitorProject project, String traceId, int hours, int limit, String containsText) {
        return search(project, traceId, hours, limit, containsText, null);
    }

    public MonitorLogSearchResult search(
            MonitorProject project, String traceId, int hours, int limit, String containsText, String severity) {
        return search(project, traceId, hours, limit, containsText, severity, null, null);
    }

    public MonitorLogSearchResult search(
            MonitorProject project, String traceId, int hours, int limit, String containsText, String severity,
            String environment, String release) {
        return search(project, traceId, hours, limit, containsText, severity, environment, release, null, Map.of());
    }

    public MonitorLogSearchResult search(
            MonitorProject project, String traceId, int hours, int limit, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags) {
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
        if (StringUtils.hasText(environment)) {
            query += " | monitor_environment=" + logqlQuote(environment);
        }
        if (StringUtils.hasText(release)) {
            query += " | monitor_release=" + logqlQuote(release);
        }
        query = appendUserAndTagFilters(query, userId, tags);
        if (StringUtils.hasText(containsText)) {
            query += " |= " + logqlQuote(containsText);
        }
        if (StringUtils.hasText(severity)) {
            String normalizedSeverity = severity.trim().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(normalizedSeverity)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported log severity");
            }
            query += " | severity_text=" + logqlQuote(normalizedSeverity);
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

    public BigDecimal countForExplore(MonitorProject project, int hours, String traceId, String containsText,
                                      String severity, String environment, String release) {
        return countForExplore(project, hours, traceId, containsText, severity, environment, release, null, Map.of());
    }

    public BigDecimal countForExplore(MonitorProject project, int hours, String traceId, String containsText,
                                      String severity, String environment, String release, String userId,
                                      Map<String, String> tags) {
        int window = Math.max(1, Math.min(168, hours));
        String query = "sum(count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                logqlQuote(project.getProjectKey());
        if (StringUtils.hasText(traceId)) {
            query += " | monitor_trace_id=" + logqlQuote(traceId.toLowerCase(java.util.Locale.ROOT));
        }
        if (StringUtils.hasText(environment)) {
            query += " | monitor_environment=" + logqlQuote(environment);
        }
        if (StringUtils.hasText(release)) {
            query += " | monitor_release=" + logqlQuote(release);
        }
        query = appendUserAndTagFilters(query, userId, tags);
        if (StringUtils.hasText(containsText)) {
            query += " |= " + logqlQuote(containsText);
        }
        if (StringUtils.hasText(severity)) {
            query += " | severity_text=" + logqlQuote(severity.toUpperCase(java.util.Locale.ROOT));
        }
        query += " [" + window + "h]))";
        String lokiQuery = query;
        try {
            JsonNode response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query")
                            .queryParam("query", "{query}")
                            .build(Map.of("query", lokiQuery)))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode value = response == null ? null : response.path("data").path("result").path(0)
                    .path("value").path(1);
            if (value == null || value.isMissingNode() || !StringUtils.hasText(value.asText())) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(value.asText());
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage query failed", e);
        }
    }

    public List<MonitorExploreAggregationResult.Bucket> aggregateForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String groupBy) {
        return aggregateForExplore(project, hours, traceId, containsText, severity, environment, release,
                null, Map.of(), groupBy);
    }

    public List<MonitorExploreAggregationResult.Bucket> aggregateForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags, String groupBy) {
        return aggregateForExplore(project, hours, traceId, containsText, severity, environment, release,
                userId, tags, groupBy, false);
    }

    public List<MonitorExploreAggregationResult.Bucket> aggregateUniqueUsersForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags, String groupBy) {
        return aggregateForExplore(project, hours, traceId, containsText, severity, environment, release,
                userId, tags, groupBy, true);
    }

    public List<MonitorExploreNumericBucket> aggregateNumericForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags, String groupBy,
            Instant end) {
        String dimension = switch (groupBy == null ? "" : groupBy.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "signal" -> "";
            case "environment" -> "monitor_environment";
            case "release" -> "monitor_release";
            case "level" -> "severity_text";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported Logs Explore numeric aggregation dimension");
        };
        if (hours < 1 || hours > 168) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs Explore numeric aggregation hours must be between 1 and 168");
        }
        int window = hours;
        String logs = "{service_name=\"observability-platform\"} | monitor_project=" +
                logqlQuote(project.getProjectKey());
        logs = appendExploreLogFilters(logs, traceId, environment, release, userId, tags, containsText, severity);
        logs += " | drop " + EXPLORE_NUMERIC_VALUE +
                " | json " + EXPLORE_NUMERIC_VALUE + "=\"value\" | __error__=\"\" | " +
                EXPLORE_NUMERIC_VALUE + "=~" + logqlQuote(EXPLORE_NUMERIC_VALUE_REGEX) + " | " +
                EXPLORE_NUMERIC_VALUE + " >= -" + MAX_FINITE_DOUBLE + " | " +
                EXPLORE_NUMERIC_VALUE + " <= " + MAX_FINITE_DOUBLE + " | __error__=\"\"";

        Map<String, Double> sums = queryNumericExploreValues(
                numericExploreQuery("sum", "sum_over_time", logs, window, dimension, true), end, dimension);
        Map<String, Double> minimums = queryNumericExploreValues(
                numericExploreQuery("min", "min_over_time", logs, window, dimension, true), end, dimension);
        Map<String, Double> maximums = queryNumericExploreValues(
                numericExploreQuery("max", "max_over_time", logs, window, dimension, true), end, dimension);
        Map<String, Long> counts = queryNumericExploreCounts(
                numericExploreQuery("sum", "count_over_time", logs, window, dimension, false), end, dimension);

        if (!sums.keySet().equals(minimums.keySet()) || !sums.keySet().equals(maximums.keySet())
                || !sums.keySet().equals(counts.keySet())) {
            throw invalidNumericExploreResponse();
        }
        return sums.keySet().stream().sorted().map(value -> new MonitorExploreNumericBucket(
                value, counts.get(value), sums.get(value), minimums.get(value), maximums.get(value))).toList();
    }

    public java.util.Set<String> uniqueUserIdsForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags) {
        int window = Math.max(1, Math.min(168, hours));
        String query = "count by (monitor_user_id) (count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                logqlQuote(project.getProjectKey());
        query = appendExploreLogFilters(query, traceId, environment, release, userId, tags, containsText, severity);
        query += " | monitor_user_id!=\"\" [" + window + "h]))";
        String lokiQuery = query;
        try {
            JsonNode response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query")
                            .queryParam("query", "{query}")
                            .build(Map.of("query", lokiQuery)))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"success".equals(response.path("status").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage returned an invalid unique-user result");
            }
            JsonNode results = response.path("data").path("result");
            if (!results.isArray() || !"vector".equals(response.path("data").path("resultType").asText())
                    || (response.path("warnings").isArray() && !response.path("warnings").isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage returned an invalid unique-user result");
            }
            java.util.Set<String> userIds = new java.util.HashSet<>();
            for (JsonNode result : results) {
                String value = result.path("metric").path("monitor_user_id").asText("");
                if (StringUtils.hasText(value)) userIds.add(value);
                if (userIds.size() > MonitorQueryService.EXPLORE_UNIQUE_USER_LIMIT) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "mixed Explore unique-user result exceeds the 10,000 signal-user limit; narrow the time range or filters");
                }
            }
            return userIds;
        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString().toLowerCase(java.util.Locale.ROOT);
            if (body.contains("series") && (body.contains("maximum") || body.contains("limit"))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Loki unique-user query exceeded its series limit; narrow the time range or filters", e);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage query failed", e);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage query failed", e);
        }
    }

    private List<MonitorExploreAggregationResult.Bucket> aggregateForExplore(
            MonitorProject project, int hours, String traceId, String containsText, String severity,
            String environment, String release, String userId, Map<String, String> tags, String groupBy,
            boolean uniqueUsers) {
        String dimension = switch (groupBy == null ? "" : groupBy.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "signal" -> "signal";
            case "environment" -> "monitor_environment";
            case "release" -> "monitor_release";
            case "level" -> "severity_text";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported Logs Explore aggregation dimension");
        };
        int window = Math.max(1, Math.min(168, hours));
        String uniqueUserFilter = uniqueUsers ? " | monitor_user_id!=\"\"" : "";
        if ("signal".equals(dimension)) {
            if (uniqueUsers) {
                String query = "count(count by (monitor_user_id) (count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                        logqlQuote(project.getProjectKey());
                query = appendExploreLogFilters(query, traceId, environment, release, userId, tags, containsText, severity);
                query += uniqueUserFilter + " [" + window + "h])))";
                return queryExploreBuckets(query, "", "logs");
            }
            BigDecimal count = countForExplore(project, window, traceId, containsText, severity, environment, release,
                    userId, tags);
            return count.signum() == 0 ? List.of() : List.of(
                    new MonitorExploreAggregationResult.Bucket("logs", count.longValue(), null));
        }
        String query;
        if (uniqueUsers) {
            query = "count by (" + dimension + ") (count by (" + dimension + ", monitor_user_id) " +
                    "(count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                    logqlQuote(project.getProjectKey());
        } else {
            query = "sum by (" + dimension + ") (count_over_time({service_name=\"observability-platform\"} | monitor_project=" +
                    logqlQuote(project.getProjectKey());
        }
        query = appendExploreLogFilters(query, traceId, environment, release, userId, tags, containsText, severity);
        query += uniqueUserFilter + " [" + window + "h]" + (uniqueUsers ? ")))" : "))");
        return queryExploreBuckets(query, dimension, null);
    }

    private String appendExploreLogFilters(
            String query, String traceId, String environment, String release, String userId,
            Map<String, String> tags, String containsText, String severity) {
        if (StringUtils.hasText(traceId)) {
            query += " | monitor_trace_id=" + logqlQuote(traceId.toLowerCase(java.util.Locale.ROOT));
        }
        if (StringUtils.hasText(environment)) {
            query += " | monitor_environment=" + logqlQuote(environment);
        }
        if (StringUtils.hasText(release)) {
            query += " | monitor_release=" + logqlQuote(release);
        }
        query = appendUserAndTagFilters(query, userId, tags);
        if (StringUtils.hasText(containsText)) {
            query += " |= " + logqlQuote(containsText);
        }
        if (StringUtils.hasText(severity)) {
            query += " | severity_text=" + logqlQuote(severity.toUpperCase(java.util.Locale.ROOT));
        }
        return query;
    }

    private String numericExploreQuery(String aggregation, String rangeAggregation, String logs,
                                       int window, String dimension, boolean unwrap) {
        String rangeQuery = logs + (unwrap
                ? " | unwrap " + EXPLORE_NUMERIC_VALUE + " | __error__=\"\""
                : "") + " [" + window + "h]";
        return aggregation + (StringUtils.hasText(dimension) ? " by (" + dimension + ")" : "") +
                " (" + rangeAggregation + "(" + rangeQuery + "))";
    }

    private Map<String, Double> queryNumericExploreValues(String query, Instant end, String dimension) {
        JsonNode results = queryNumericExploreResults(query, end);
        Map<String, Double> values = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String group = numericExploreGroup(result, dimension);
            JsonNode sample = numericExploreSample(result);
            double value = finiteNumericValue(sample.get(1));
            if (values.containsKey(group)) throw invalidNumericExploreResponse();
            if (values.size() >= MAX_EXPLORE_NUMERIC_GROUPS) throw tooManyNumericExploreGroups();
            values.put(group, value);
        }
        return values;
    }

    private Map<String, Long> queryNumericExploreCounts(String query, Instant end, String dimension) {
        JsonNode results = queryNumericExploreResults(query, end);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String group = numericExploreGroup(result, dimension);
            JsonNode sample = numericExploreSample(result);
            long count;
            try {
                count = decimalValue(sample.get(1)).longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                throw invalidNumericExploreResponse();
            }
            if (count < 0 || counts.containsKey(group)) throw invalidNumericExploreResponse();
            if (counts.size() >= MAX_EXPLORE_NUMERIC_GROUPS) throw tooManyNumericExploreGroups();
            counts.put(group, count);
        }
        return counts;
    }

    private JsonNode queryNumericExploreResults(String query, Instant end) {
        JsonNode response;
        try {
            response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query")
                            .queryParam("query", "{query}")
                            .queryParam("time", toNanos(end))
                            .build(Map.of("query", query)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException error) {
            String body = error.getResponseBodyAsString().toLowerCase(java.util.Locale.ROOT);
            if (body.contains("series") && (body.contains("maximum") || body.contains("limit"))) {
                throw tooManyNumericExploreGroups();
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage numeric Explore query failed");
        } catch (RestClientException ignored) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "log storage numeric Explore query failed");
        }
        JsonNode warnings = response == null ? null : response.get("warnings");
        JsonNode data = response == null ? null : response.get("data");
        JsonNode results = data == null ? null : data.get("result");
        if (response == null || !"success".equals(response.path("status").asText())
                || (warnings != null && !warnings.isNull() && (!warnings.isArray() || !warnings.isEmpty()))
                || data == null || !"vector".equals(data.path("resultType").asText())
                || results == null || !results.isArray()) {
            throw invalidNumericExploreResponse();
        }
        return results;
    }

    private String numericExploreGroup(JsonNode result, String dimension) {
        JsonNode metric = result == null ? null : result.get("metric");
        if (metric == null || !metric.isObject()) throw invalidNumericExploreResponse();
        if (!StringUtils.hasText(dimension)) {
            if (!metric.isEmpty()) throw invalidNumericExploreResponse();
            return "logs";
        }
        if (metric.size() > 1 || (metric.size() == 1 && !metric.has(dimension))) {
            throw invalidNumericExploreResponse();
        }
        JsonNode value = metric.get(dimension);
        if (value == null) return "(empty)";
        if (!value.isTextual()) throw invalidNumericExploreResponse();
        return StringUtils.hasText(value.asText()) ? value.asText() : "(empty)";
    }

    private JsonNode numericExploreSample(JsonNode result) {
        JsonNode sample = result == null ? null : result.get("value");
        if (sample == null || !sample.isArray() || sample.size() != 2) {
            throw invalidNumericExploreResponse();
        }
        finiteNumericValue(sample.get(0));
        return sample;
    }

    private double finiteNumericValue(JsonNode value) {
        double numeric = decimalValue(value).doubleValue();
        if (!Double.isFinite(numeric)) throw invalidNumericExploreResponse();
        return numeric;
    }

    private BigDecimal decimalValue(JsonNode value) {
        try {
            if (value != null && value.isTextual()) return new BigDecimal(value.textValue());
            if (value != null && value.isNumber()) return value.decimalValue();
        } catch (ArithmeticException | NumberFormatException ignored) {
            throw invalidNumericExploreResponse();
        }
        throw invalidNumericExploreResponse();
    }

    private ResponseStatusException invalidNumericExploreResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "log storage returned an invalid numeric Explore result");
    }

    private ResponseStatusException tooManyNumericExploreGroups() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Logs Explore numeric result exceeds 1,000 groups; narrow the time range or filters");
    }

    private List<MonitorExploreAggregationResult.Bucket> queryExploreBuckets(
            String query, String dimension, String signalValue) {
        String lokiQuery = query;
        try {
            JsonNode response = loki.get()
                    .uri(uri -> uri.path("/loki/api/v1/query")
                            .queryParam("query", "{query}")
                            .build(Map.of("query", lokiQuery)))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode results = response == null ? null : response.path("data").path("result");
            if (results == null || !results.isArray()) return List.of();
            List<MonitorExploreAggregationResult.Bucket> buckets = new ArrayList<>();
            for (JsonNode result : results) {
                String value = signalValue == null ? result.path("metric").path(dimension).asText("") : signalValue;
                JsonNode count = result.path("value").path(1);
                if (!count.isMissingNode() && StringUtils.hasText(count.asText())) {
                    buckets.add(new MonitorExploreAggregationResult.Bucket(
                            StringUtils.hasText(value) ? value : "(empty)", new BigDecimal(count.asText()).longValue(), null));
                }
            }
            return buckets.stream().sorted(java.util.Comparator
                    .comparingLong(MonitorExploreAggregationResult.Bucket::count).reversed()
                    .thenComparing(MonitorExploreAggregationResult.Bucket::value)).limit(100).toList();
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
                        "UNION ALL SELECT event_id FROM monitor.profile_event WHERE project_id=? AND trace_id=? " +
                        "LIMIT 1)",
                Long.class,
                projectKey, traceId, projectKey, traceId, projectKey, traceId, projectKey, traceId,
                projectKey, traceId, projectKey, traceId
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

    private String appendUserAndTagFilters(String query, String userId, Map<String, String> tags) {
        if (StringUtils.hasText(userId)) query += " | monitor_user_id=" + logqlQuote(userId);
        if (tags != null) {
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(tags).entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                query += " | monitor_tags=~" + logqlQuote(MonitorLogTagMetadata.exactTokenRegex(key, value));
            }
        }
        return query;
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
