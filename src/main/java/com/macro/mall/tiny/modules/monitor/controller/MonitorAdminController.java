package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertSilenceRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreNumericBucket;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueBulkStatusRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteOption;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertRuleView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataScrubbingRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataScrubbingSettings;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDashboardView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceSpan;
import com.macro.mall.tiny.modules.monitor.dto.MonitorProjectMemberRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSavedExploreQueryRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorSavedExploreQueryView;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapResolvedPosition;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapStackFrame;
import com.macro.mall.tiny.modules.monitor.dto.SourceMapStackResolveRequest;
import com.macro.mall.tiny.modules.monitor.model.*;
import com.macro.mall.tiny.modules.monitor.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/monitor/admin")
@RequiredArgsConstructor
public class MonitorAdminController {

    private final MonitorAdminService adminService;
    private final MonitorQueryService queryService;
    private final MonitorReplayService replayService;
    private final MonitorSourceMapService sourceMapService;
    private final ObjectMapper objectMapper;
    private final MonitorProjectService projectService;
    private final MonitorProjectAccessService projectAccessService;
    private final MonitorAlertSilenceService silenceService;
    private final MonitorAlertDeliveryService deliveryService;
    private final MonitorLogQueryService logQueryService;
    private final MonitorReleaseHealthService releaseHealthService;
    private final MonitorSavedExploreQueryService savedExploreQueryService;
    private final MonitorDashboardService dashboardService;
    private final MonitorAlertNotificationRouteService notificationRouteService;

    @PostMapping("/projects")
    public CommonResult<com.macro.mall.tiny.modules.monitor.dto.MonitorProjectCredentials> createProject(
            @Valid @RequestBody com.macro.mall.tiny.modules.monitor.dto.MonitorProjectRequest request) {
        return CommonResult.success(projectService.create(request));
    }

    @PostMapping("/projects/{projectKey}/rotate-keys")
    public CommonResult<com.macro.mall.tiny.modules.monitor.dto.MonitorProjectCredentials> rotateKeys(
            @PathVariable String projectKey) {
        adminService.requireProjectOwner(projectKey);
        return CommonResult.success(projectService.rotateKeys(projectKey));
    }

    @GetMapping("/projects")
    public CommonResult<List<MonitorProject>> projects() {
        return CommonResult.success(projectAccessService.listProjects());
    }

    @GetMapping("/projects/{projectKey}/members")
    public CommonResult<List<MonitorProjectMember>> projectMembers(@PathVariable String projectKey) {
        return CommonResult.success(projectAccessService.listMembers(projectKey));
    }

    @PutMapping("/projects/{projectKey}/members")
    public CommonResult<MonitorProjectMember> updateProjectMember(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorProjectMemberRequest request) {
        return CommonResult.success(projectAccessService.addOrUpdateMember(projectKey, request));
    }

    @DeleteMapping("/projects/{projectKey}/members/{adminId}")
    public CommonResult<Void> removeProjectMember(
            @PathVariable String projectKey,
            @PathVariable Long adminId) {
        projectAccessService.removeMember(projectKey, adminId);
        return CommonResult.success(null);
    }

    @PostMapping("/projects/{projectKey}/members/{adminId}/transfer-owner")
    public CommonResult<MonitorProjectMember> transferProjectOwner(
            @PathVariable String projectKey,
            @PathVariable Long adminId) {
        return CommonResult.success(projectAccessService.transferOwner(projectKey, adminId));
    }

    @GetMapping("/{projectKey}/dashboard")
    public CommonResult<Map<String, Object>> dashboard(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.dashboard(project, hours, environment, release));
    }

    @GetMapping("/{projectKey}/dashboards")
    public CommonResult<List<MonitorDashboardView>> dashboards(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(dashboardService.list(project, canModifyProject(projectKey)));
    }

    @GetMapping("/{projectKey}/dashboards/{id}")
    public CommonResult<MonitorDashboardView> dashboardById(
            @PathVariable String projectKey,
            @PathVariable Long id) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(dashboardService.get(project, id, canModifyProject(projectKey)));
    }

    @PostMapping("/{projectKey}/dashboards")
    public CommonResult<MonitorDashboardView> createDashboard(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorDashboardRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        return CommonResult.success(dashboardService.create(project, request));
    }

    @PutMapping("/{projectKey}/dashboards/{id}")
    public CommonResult<MonitorDashboardView> updateDashboard(
            @PathVariable String projectKey,
            @PathVariable Long id,
            @Valid @RequestBody MonitorDashboardRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        return CommonResult.success(dashboardService.update(project, id, request));
    }

    @DeleteMapping("/{projectKey}/dashboards/{id}")
    public CommonResult<Void> deleteDashboard(@PathVariable String projectKey, @PathVariable Long id) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        dashboardService.delete(project, id);
        return CommonResult.success(null);
    }

    private boolean canModifyProject(String projectKey) {
        try {
            projectAccessService.requireProject(projectKey, true);
            return true;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) return false;
            throw e;
        }
    }

    @GetMapping("/{projectKey}/logs")
    public CommonResult<com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult> logs(
            @PathVariable String projectKey,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String tagKey,
            @RequestParam(required = false) String tagValue) {
        MonitorProject project = adminService.requireProject(projectKey);
        if (StringUtils.hasText(userId) && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (StringUtils.hasText(tagKey) != StringUtils.hasText(tagValue)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagKey and tagValue must be supplied together");
        }
        if (StringUtils.hasText(tagKey) && (tagKey.length() > 64 || tagValue.length() > 128
                || !tagKey.matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid tag filter");
        }
        Map<String, String> tags = StringUtils.hasText(tagKey) ? Map.of(tagKey, tagValue) : Map.of();
        return CommonResult.success(logQueryService.search(project, traceId, hours, limit, query, null,
                null, null, userId, tags));
    }

    @GetMapping("/{projectKey}/explore")
    public CommonResult<MonitorExploreResult> explore(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String tagKey,
            @RequestParam(required = false) String tagValue,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        MonitorProject project = adminService.requireProject(projectKey);
        boolean hasUserFilter = StringUtils.hasText(userId);
        boolean hasTagKey = StringUtils.hasText(tagKey);
        boolean hasTagValue = StringUtils.hasText(tagValue);
        MonitorExploreQueryParser.Parsed parsedQuery = MonitorExploreQueryParser.parse(query);
        for (MonitorExploreQueryParser.Term term : parsedQuery.terms()) {
            if ("user".equals(term.field()) && term.value().length() > 128) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
            }
            if ("tag".equals(term.field()) && term.value().length() > 128) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagValue must be at most 128 characters");
            }
        }
        if (hasTagKey != hasTagValue) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey and tagValue must be supplied together");
        }
        if (hasUserFilter && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (hasTagKey && (tagKey.length() > 64 || tagValue.length() > 128
                || !tagKey.matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey must use 1 to 64 letters, digits, dot, underscore or hyphen and tagValue at most 128 characters");
        }
        if ("logs".equalsIgnoreCase(type)) {
            if (hours > 168) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Logs Explore supports time ranges up to 7 days");
            }
            if (parsedQuery.expression() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Logs Explore supports one trace filter at a time");
            }
            String queryTraceId = traceId;
            String queryEnvironment = environment;
            String queryRelease = release;
            String queryUserId = hasUserFilter ? userId : null;
            Map<String, String> queryTags = hasTagKey ? new LinkedHashMap<>(Map.of(tagKey, tagValue))
                    : new LinkedHashMap<>();
            String severity = null;
            for (MonitorExploreQueryParser.Term term : parsedQuery.terms()) {
                if (term.negated() || !("trace".equals(term.field()) || "environment".equals(term.field())
                        || "release".equals(term.field()) || "level".equals(term.field())
                        || "user".equals(term.field()) || "tag".equals(term.field()))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Logs Explore supports environment, release, trace, severity, user, tag and text filters only");
                }
                if ("trace".equals(term.field())) {
                    if (StringUtils.hasText(queryTraceId) && !queryTraceId.equalsIgnoreCase(term.value())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "traceId and trace filters must match");
                    }
                    queryTraceId = term.value();
                } else if ("environment".equals(term.field())) {
                    if (StringUtils.hasText(queryEnvironment) && !queryEnvironment.equals(term.value())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "environment query parameters must match");
                    }
                    queryEnvironment = term.value();
                } else if ("release".equals(term.field())) {
                    if (StringUtils.hasText(queryRelease) && !queryRelease.equals(term.value())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "release query parameters must match");
                    }
                    queryRelease = term.value();
                } else if ("user".equals(term.field())) {
                    if (StringUtils.hasText(queryUserId) && !queryUserId.equals(term.value())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user filters must match");
                    }
                    queryUserId = term.value();
                } else if ("tag".equals(term.field())) {
                    addExploreTag(queryTags, term.tagKey(), term.value());
                } else {
                    String normalized = term.value().trim().toUpperCase(java.util.Locale.ROOT);
                    if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(normalized)
                            || (severity != null && !severity.equals(normalized))) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported log severity filter");
                    }
                    severity = normalized;
                }
            }
            int safeLimit = Math.max(1, Math.min(200, limit));
            int safeOffset = Math.max(0, Math.min(400, offset));
            var result = logQueryService.search(project, queryTraceId, hours,
                    Math.min(500, safeOffset + safeLimit + 1), parsedQuery.text(), severity,
                    queryEnvironment, queryRelease, queryUserId, queryTags);
            String requestedEnvironment = queryEnvironment;
            String requestedRelease = queryRelease;
            List<Map<String, Object>> events = result.entries().stream()
                    .skip(safeOffset)
                    .limit(safeLimit + 1L)
                    .map(entry -> {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("signal_type", "logs");
                        event.put("event_id", entry.timestamp() + ":" + entry.line().hashCode());
                        event.put("event_time", entry.timestamp());
                        event.put("title", entry.line());
                        event.put("release", logMetadata(entry, "monitor_release", requestedRelease));
                        event.put("environment", logMetadata(entry, "monitor_environment", requestedEnvironment));
                        event.put("page_url", "");
                        event.put("trace_id", logTraceId(entry, result.traceId()));
                        event.put("user_id", entry.metadata().get("monitor_user_id"));
                        event.put("monitor_tags", entry.metadata().get("monitor_tags"));
                        event.put("fingerprint", "");
                        event.put("session_id", "");
                        event.put("payload", entry.line());
                        return event;
                    })
                    .toList();
            boolean hasMore = events.size() > safeLimit;
            if (hasMore) events = new java.util.ArrayList<>(events.subList(0, safeLimit));
            return CommonResult.success(new MonitorExploreResult(events, hasMore, safeLimit));
        }
        if (!StringUtils.hasText(type) && supportsMixedExploreLogs(
                hours, parsedQuery, environment, release, userId, tagKey, tagValue, traceId)) {
            return CommonResult.success(exploreWithLogs(project, hours, environment, release, traceId,
                    query, parsedQuery, limit, offset, userId, tagKey, tagValue));
        }
        return CommonResult.success(queryService.explore(
                project, hours, type, environment, release, traceId, query,
                hasUserFilter ? userId : null, hasTagKey ? tagKey : null, hasTagValue ? tagValue : null,
                limit, offset
        ));
    }

    private boolean supportsMixedExploreLogs(
            int hours,
            MonitorExploreQueryParser.Parsed query,
            String environment,
            String release,
            String userId,
            String tagKey,
            String tagValue,
            String traceId) {
        if (hours > 168 || query.expression() != null) {
            return false;
        }
        String queryTraceId = null;
        String severity = null;
        String queryEnvironment = environment;
        String queryRelease = release;
        for (MonitorExploreQueryParser.Term term : query.terms()) {
            if (term.negated()) return false;
            if ("trace".equals(term.field())) {
                if (queryTraceId != null && !queryTraceId.equalsIgnoreCase(term.value())) return false;
                queryTraceId = term.value();
            } else if ("level".equals(term.field())) {
                String normalized = term.value().trim().toUpperCase(java.util.Locale.ROOT);
                if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(normalized)) return false;
                if (severity != null && !severity.equals(normalized)) return false;
                severity = normalized;
            } else if ("environment".equals(term.field())) {
                if (StringUtils.hasText(queryEnvironment) && !queryEnvironment.equals(term.value())) return false;
                queryEnvironment = term.value();
            } else if ("release".equals(term.field())) {
                if (StringUtils.hasText(queryRelease) && !queryRelease.equals(term.value())) return false;
                queryRelease = term.value();
            } else if ("user".equals(term.field()) || "tag".equals(term.field())) {
                // Loki has exact structured metadata filters for these fields.
            } else {
                return false;
            }
        }
        return !StringUtils.hasText(traceId) || queryTraceId == null || traceId.equalsIgnoreCase(queryTraceId);
    }

    private MonitorExploreResult exploreWithLogs(
            MonitorProject project,
            int hours,
            String environment,
            String release,
            String traceId,
            String query,
            MonitorExploreQueryParser.Parsed parsedQuery,
            int limit,
            int offset,
            String userId,
            String tagKey,
            String tagValue) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        int safeOffset = Math.max(0, offset);
        if (safeOffset >= 500) return new MonitorExploreResult(List.of(), false, safeLimit);
        safeLimit = Math.min(safeLimit, 500 - safeOffset);
        int fetchLimit = Math.min(500, safeOffset + safeLimit + 1);
        String queryTraceId = StringUtils.hasText(traceId) ? traceId.trim() : null;
        String queryEnvironment = environment;
        String queryRelease = release;
        String severity = null;
        String queryUserId = StringUtils.hasText(userId) ? userId.trim() : null;
        Map<String, String> queryTags = new LinkedHashMap<>();
        if (StringUtils.hasText(tagKey) && StringUtils.hasText(tagValue)) addExploreTag(queryTags, tagKey, tagValue);
        for (MonitorExploreQueryParser.Term term : parsedQuery.terms()) {
            if ("trace".equals(term.field())) queryTraceId = term.value();
            if ("level".equals(term.field())) severity = term.value();
            if ("environment".equals(term.field())) queryEnvironment = term.value();
            if ("release".equals(term.field())) queryRelease = term.value();
            if ("user".equals(term.field())) {
                if (StringUtils.hasText(queryUserId) && !queryUserId.equals(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user filters must match");
                }
                queryUserId = term.value();
            }
            if ("tag".equals(term.field())) addExploreTag(queryTags, term.tagKey(), term.value());
        }

        MonitorExploreResult events = queryService.explore(project, hours, null, environment, release, traceId, query,
                queryUserId, tagKey, tagValue, fetchLimit, 0);
        var logs = logQueryService.search(project, queryTraceId, hours, Math.min(500, fetchLimit),
                parsedQuery.text(), severity, queryEnvironment, queryRelease, queryUserId, queryTags);
        List<Map<String, Object>> combined = new ArrayList<>(events.events());
        for (var entry : logs.entries()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("signal_type", "logs");
            event.put("event_id", entry.timestamp() + ":" + entry.line().hashCode());
            event.put("event_time", entry.timestamp());
            event.put("title", entry.line());
            event.put("release", logMetadata(entry, "monitor_release", queryRelease));
            event.put("environment", logMetadata(entry, "monitor_environment", queryEnvironment));
            event.put("page_url", "");
            event.put("trace_id", logTraceId(entry, logs.traceId()));
            event.put("user_id", entry.metadata().get("monitor_user_id"));
            event.put("monitor_tags", entry.metadata().get("monitor_tags"));
            event.put("fingerprint", "");
            event.put("session_id", "");
            event.put("payload", entry.line());
            combined.add(event);
        }
        combined.sort(Comparator.comparing(MonitorAdminController::eventTime)
                .reversed()
                .thenComparing(event -> String.valueOf(event.get("signal_type")))
                .thenComparing(event -> String.valueOf(event.get("event_id"))));
        if (combined.size() > 500) combined = new ArrayList<>(combined.subList(0, 500));
        int pageEnd = Math.min(combined.size(), safeOffset + safeLimit);
        boolean hasMore = pageEnd < combined.size();
        List<Map<String, Object>> page = safeOffset >= combined.size()
                ? List.of()
                : new ArrayList<>(combined.subList(safeOffset, pageEnd));
        return new MonitorExploreResult(page, hasMore, safeLimit);
    }

    private static void addExploreTag(Map<String, String> tags, String key, String value) {
        String previous = tags.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate tag filters must match");
        }
    }

    private static String logTraceId(
            com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult.Entry entry,
            String queryTraceId) {
        String traceId = logMetadata(entry, "monitor_trace_id", null);
        return StringUtils.hasText(traceId) ? traceId : queryTraceId;
    }

    private static String logMetadata(
            com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult.Entry entry,
            String key,
            String fallback) {
        String value = entry.metadata().get(key);
        if (!StringUtils.hasText(value)) value = entry.labels().get(key);
        return StringUtils.hasText(value) ? value : (fallback == null ? "" : fallback);
    }

    private static Instant eventTime(Map<String, Object> event) {
        Object value = event.get("event_time");
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof CharSequence text) {
            try { return Instant.parse(text); }
            catch (RuntimeException ignored) { return Instant.MIN; }
        }
        return Instant.MIN;
    }

    @GetMapping("/{projectKey}/explore/aggregate")
    public CommonResult<MonitorExploreAggregationResult> exploreAggregate(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String tagKey,
            @RequestParam(required = false) String tagValue,
            @RequestParam(defaultValue = "signal") String groupBy,
            @RequestParam(defaultValue = "count") String aggregation,
            @RequestParam(defaultValue = "value") String field) {
        MonitorProject project = adminService.requireProject(projectKey);
        boolean logsOnly = "logs".equalsIgnoreCase(type);
        if (logsOnly && hours > 168) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Logs Explore supports time ranges up to 7 days");
        }
        if (StringUtils.hasText(type) && !List.of("error", "performance", "behavior", "replay", "metric", "profile", "logs")
                .contains(type.trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore signal type");
        }
        if (StringUtils.hasText(userId) && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (StringUtils.hasText(tagKey) != StringUtils.hasText(tagValue)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagKey and tagValue must be supplied together");
        }
        if (StringUtils.hasText(tagKey) && (tagKey.length() > 64 || tagValue.length() > 128
                || !tagKey.matches("[A-Za-z0-9_.-]{1,64}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey must be at most 64 characters and tagValue at most 128 characters");
        }
        String normalizedGroupBy = groupBy == null ? "" : groupBy.trim();
        if (!List.of("signal", "environment", "release", "url", "level").contains(normalizedGroupBy.toLowerCase())
                && !normalizedGroupBy.matches("(?i)tag\\.[A-Za-z0-9_.-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore aggregation dimension");
        }
        String normalizedAggregation = aggregation.trim().toLowerCase();
        if (!List.of("count", "count_unique", "sum", "avg", "min", "max", "p50", "p75", "p95")
                .contains(normalizedAggregation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore aggregation");
        }
        if ("count_unique".equals(normalizedAggregation)) {
            String normalizedField = field.trim().toLowerCase();
            if (!List.of("user", "event", "trace", "url").contains(normalizedField)
                    && !field.trim().matches("(?i)tag\\.[A-Za-z0-9_.-]{1,64}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore unique-count field");
            }
            if (!StringUtils.hasText(type) && hours <= 168 && "user".equals(normalizedField)
                    && !"signal".equalsIgnoreCase(normalizedGroupBy)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "mixed Explore count_unique(user) currently supports signal grouping only");
            }
        } else if (List.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(normalizedAggregation)) {
            if (!"value".equalsIgnoreCase(field.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "numeric aggregations support the value field only");
            }
            boolean singleSignalNumeric = List.of("performance", "metric")
                    .contains(type == null ? "" : type.trim().toLowerCase());
            boolean simpleNumeric = List.of("sum", "avg", "min", "max").contains(normalizedAggregation);
            if (!singleSignalNumeric && !(simpleNumeric && (logsOnly || !StringUtils.hasText(type)))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "numeric aggregations require Performance or Metrics signal type");
            }
            if (simpleNumeric && (logsOnly || !StringUtils.hasText(type))) {
                if (hours > 168) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Mixed and Logs numeric Explore supports time ranges up to 7 days");
                }
                if (!List.of("signal", "environment", "release", "level")
                        .contains(normalizedGroupBy.toLowerCase(java.util.Locale.ROOT))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Mixed and Logs numeric Explore supports signal, environment, release or level grouping");
                }
                if (!supportsExploreLogAggregation(environment, release, traceId, query,
                        normalizedGroupBy.toLowerCase(java.util.Locale.ROOT))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Mixed and Logs numeric Explore supports only Loki-compatible filters");
                }
            }
        }
        if (logsOnly && (!List.of("signal", "environment", "release", "level")
                .contains(normalizedGroupBy.toLowerCase())
                || !("count".equals(normalizedAggregation) && "value".equalsIgnoreCase(field.trim()))
                    && !("count_unique".equals(normalizedAggregation) && "user".equalsIgnoreCase(field.trim()))
                    && !List.of("sum", "avg", "min", "max").contains(normalizedAggregation))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs Explore supports count(), count_unique(user) and numeric value aggregations grouped by signal, environment, release or level");
        }
        boolean numericValueAggregation = List.of("sum", "avg", "min", "max").contains(normalizedAggregation);
        if (numericValueAggregation && (logsOnly || !StringUtils.hasText(type))) {
            return CommonResult.success(exploreNumericAggregate(project, hours, logsOnly, environment, release,
                    traceId, query, userId, tagKey, tagValue, normalizedGroupBy.toLowerCase(java.util.Locale.ROOT),
                    normalizedAggregation));
        }
        MonitorExploreAggregationResult result;
        if (logsOnly) {
            List<MonitorExploreAggregationResult.Bucket> buckets = exploreLogBuckets(
                    project, hours, environment, release, traceId, query, normalizedGroupBy,
                    userId, tagKey, tagValue, "count_unique".equals(normalizedAggregation));
            result = new MonitorExploreAggregationResult(normalizedGroupBy, normalizedAggregation,
                    "count_unique".equals(normalizedAggregation) ? "user" : "value", buckets);
        } else if (!StringUtils.hasText(type) && hours <= 168
                && "count_unique".equals(normalizedAggregation) && "user".equalsIgnoreCase(field.trim())) {
            if (!supportsExploreLogAggregation(environment, release, traceId, query, "signal")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "mixed Explore count_unique(user) supports only Loki-compatible filters: text, trace, severity, environment, release, user and tags");
            }
            ExploreLogFilters filters = resolveExploreLogFilters(
                    environment, release, traceId, query, userId, tagKey, tagValue);
            Map<String, Set<String>> usersBySignal = new LinkedHashMap<>();
            queryService.exploreUniqueUsersBySignal(project, hours, environment, release, traceId,
                    query, userId, tagKey, tagValue).forEach((signal, ids) ->
                    usersBySignal.put(signal, new java.util.HashSet<>(ids)));
            Set<String> logUsers = logQueryService.uniqueUserIdsForExplore(project, hours,
                    filters.traceId(), filters.containsText(), filters.severity(), filters.environment(),
                    filters.release(), filters.userId(), filters.tags());
            if (!logUsers.isEmpty()) {
                usersBySignal.computeIfAbsent("logs", ignored -> new java.util.HashSet<>()).addAll(logUsers);
            }
            int signalUserCount = usersBySignal.values().stream().mapToInt(Set::size).sum();
            if (signalUserCount > MonitorQueryService.EXPLORE_UNIQUE_USER_LIMIT) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "mixed Explore unique-user result exceeds the 10,000 signal-user limit; narrow the time range or filters");
            }
            List<MonitorExploreAggregationResult.Bucket> buckets = usersBySignal.entrySet().stream()
                    .map(entry -> new MonitorExploreAggregationResult.Bucket(entry.getKey(), entry.getValue().size(), null))
                    .sorted(Comparator.comparingLong(MonitorExploreAggregationResult.Bucket::count).reversed()
                            .thenComparing(MonitorExploreAggregationResult.Bucket::value))
                    .toList();
            result = new MonitorExploreAggregationResult("signal", "count_unique", "user", buckets);
        } else {
            result = queryService.exploreAggregate(project, hours, type, environment, release, traceId,
                    query, userId, tagKey, tagValue, groupBy, aggregation, field);
            if (!StringUtils.hasText(type) && "count".equals(normalizedAggregation)
                    && hours <= 168 && List.of("signal", "environment", "release", "level")
                    .contains(normalizedGroupBy.toLowerCase())
                    && supportsExploreLogAggregation(environment, release, traceId, query, normalizedGroupBy)) {
                List<MonitorExploreAggregationResult.Bucket> logBuckets = exploreLogBuckets(
                        project, hours, environment, release, traceId, query, normalizedGroupBy,
                        userId, tagKey, tagValue, false);
                if (!logBuckets.isEmpty()) {
                    Map<String, Long> merged = new LinkedHashMap<>();
                    for (MonitorExploreAggregationResult.Bucket bucket : result.buckets()) {
                        merged.put(bucket.value(), bucket.count());
                    }
                    for (MonitorExploreAggregationResult.Bucket bucket : logBuckets) {
                        String value = "level".equalsIgnoreCase(normalizedGroupBy)
                                ? bucket.value().toLowerCase(java.util.Locale.ROOT) : bucket.value();
                        merged.merge(value, bucket.count(), Long::sum);
                    }
                    List<MonitorExploreAggregationResult.Bucket> buckets = merged.entrySet().stream()
                            .map(entry -> new MonitorExploreAggregationResult.Bucket(entry.getKey(), entry.getValue(), null))
                            .sorted(Comparator.comparingLong(MonitorExploreAggregationResult.Bucket::count).reversed()
                                    .thenComparing(MonitorExploreAggregationResult.Bucket::value))
                            .limit(100).toList();
                    result = new MonitorExploreAggregationResult(result.groupBy(), result.aggregation(), result.field(), buckets);
                }
            }
        }
        return CommonResult.success(result);
    }

    private List<MonitorExploreAggregationResult.Bucket> exploreLogBuckets(
            MonitorProject project, int hours, String environment, String release, String traceId,
            String query, String groupBy, String userId, String tagKey, String tagValue, boolean uniqueUsers) {
        ExploreLogFilters filters = resolveExploreLogFilters(environment, release, traceId, query,
                userId, tagKey, tagValue);
        if (uniqueUsers) {
            return logQueryService.aggregateUniqueUsersForExplore(project, hours, filters.traceId(),
                    filters.containsText(), filters.severity(), filters.environment(), filters.release(),
                    filters.userId(), filters.tags(), groupBy);
        }
        return logQueryService.aggregateForExplore(project, hours, filters.traceId(), filters.containsText(),
                filters.severity(), filters.environment(), filters.release(), filters.userId(), filters.tags(), groupBy);
    }

    private MonitorExploreAggregationResult exploreNumericAggregate(
            MonitorProject project, int hours, boolean logsOnly, String environment, String release,
            String traceId, String query, String userId, String tagKey, String tagValue,
            String groupBy, String aggregation) {
        Instant end = Instant.now();
        ExploreLogFilters filters = resolveExploreLogFilters(environment, release, traceId, query,
                userId, tagKey, tagValue);
        List<MonitorExploreNumericBucket> signalBuckets = logsOnly ? List.of()
                : queryService.exploreNumericBuckets(project, hours, environment, release, traceId,
                        query, userId, tagKey, tagValue, groupBy, end);
        List<MonitorExploreNumericBucket> logBuckets = logQueryService.aggregateNumericForExplore(project, hours,
                filters.traceId(), filters.containsText(), filters.severity(), filters.environment(),
                filters.release(), filters.userId(), filters.tags(), groupBy, end);

        Map<String, NumericBucketAccumulator> merged = new LinkedHashMap<>();
        mergeNumericBuckets(merged, signalBuckets, groupBy);
        mergeNumericBuckets(merged, logBuckets, groupBy);

        List<MonitorExploreAggregationResult.Bucket> buckets = merged.entrySet().stream()
                .map(entry -> {
                    NumericBucketAccumulator bucket = entry.getValue();
                    double aggregateValue = switch (aggregation) {
                        case "sum" -> bucket.sum;
                        case "avg" -> bucket.sum / bucket.count;
                        case "min" -> bucket.min;
                        case "max" -> bucket.max;
                        default -> throw new IllegalStateException("unsupported numeric aggregation");
                    };
                    if (!Double.isFinite(aggregateValue)) throw numericExploreLimitExceeded();
                    return new MonitorExploreAggregationResult.Bucket(entry.getKey(), bucket.count, aggregateValue);
                })
                .sorted(Comparator.comparingDouble((MonitorExploreAggregationResult.Bucket bucket) ->
                                bucket.aggregateValue()).reversed()
                        .thenComparing(MonitorExploreAggregationResult.Bucket::value))
                .limit(100)
                .toList();
        return new MonitorExploreAggregationResult(groupBy, aggregation, "value", buckets);
    }

    private void mergeNumericBuckets(Map<String, NumericBucketAccumulator> merged,
                                     List<MonitorExploreNumericBucket> buckets, String groupBy) {
        if (buckets == null) return;
        if (buckets.size() > 1000) throw numericExploreLimitExceeded();
        for (MonitorExploreNumericBucket bucket : buckets) {
            if (bucket == null || bucket.count() <= 0 || !Double.isFinite(bucket.sum())
                    || !Double.isFinite(bucket.min()) || !Double.isFinite(bucket.max())
                    || bucket.min() > bucket.max()) {
                throw numericExploreLimitExceeded();
            }
            String value = normalizeNumericGroupValue(bucket.value(), groupBy);
            NumericBucketAccumulator aggregate = merged.computeIfAbsent(value, ignored -> new NumericBucketAccumulator());
            aggregate.add(bucket);
            if (merged.size() > 1000) throw numericExploreLimitExceeded();
        }
    }

    private String normalizeNumericGroupValue(String value, String groupBy) {
        String normalized = StringUtils.hasText(value) ? value : "(empty)";
        if ("(empty)".equalsIgnoreCase(normalized.trim())) normalized = "(empty)";
        if ("level".equals(groupBy)) normalized = normalized.toLowerCase(java.util.Locale.ROOT);
        return normalized;
    }

    private ResponseStatusException numericExploreLimitExceeded() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "numeric Explore exceeds supported group, count or value limits; narrow the time range or filters");
    }

    private static final class NumericBucketAccumulator {
        private long count;
        private double sum;
        private double min;
        private double max;
        private boolean initialized;

        private void add(MonitorExploreNumericBucket bucket) {
            final long nextCount;
            try {
                nextCount = Math.addExact(count, bucket.count());
            } catch (ArithmeticException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "numeric Explore exceeds supported group, count or value limits; narrow the time range or filters");
            }
            double nextSum = sum + bucket.sum();
            if (!Double.isFinite(nextSum)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "numeric Explore exceeds supported group, count or value limits; narrow the time range or filters");
            }
            count = nextCount;
            sum = nextSum;
            if (!initialized) {
                min = bucket.min();
                max = bucket.max();
                initialized = true;
            } else {
                min = Math.min(min, bucket.min());
                max = Math.max(max, bucket.max());
            }
        }
    }

    private record ExploreLogFilters(String traceId, String containsText, String severity,
                                     String environment, String release, String userId, Map<String, String> tags) {
    }

    private ExploreLogFilters resolveExploreLogFilters(
            String environment, String release, String traceId, String query, String userId,
            String tagKey, String tagValue) {
        MonitorExploreQueryParser.Parsed parsed = MonitorExploreQueryParser.parse(query);
        if (parsed.expression() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Logs Explore aggregation supports one trace and severity filter at a time");
        }
        String queryTraceId = traceId;
        String queryEnvironment = environment;
        String queryRelease = release;
        String severity = null;
        for (MonitorExploreQueryParser.Term term : parsed.terms()) {
            if (term.negated() || !("trace".equals(term.field()) || "level".equals(term.field())
                    || "environment".equals(term.field()) || "release".equals(term.field())
                    || "user".equals(term.field()) || "tag".equals(term.field()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Logs Explore aggregation supports environment, release, trace, severity, user, tag and free-text filters only");
            }
            if ("trace".equals(term.field())) {
                if (StringUtils.hasText(queryTraceId) && !queryTraceId.equalsIgnoreCase(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "traceId and trace filters must match");
                }
                queryTraceId = term.value();
            } else if ("environment".equals(term.field())) {
                if (StringUtils.hasText(queryEnvironment) && !queryEnvironment.equals(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "environment filters must match");
                }
                queryEnvironment = term.value();
            } else if ("release".equals(term.field())) {
                if (StringUtils.hasText(queryRelease) && !queryRelease.equals(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "release filters must match");
                }
                queryRelease = term.value();
            } else if ("user".equals(term.field()) || "tag".equals(term.field())) {
                // Applied to the Loki query after validating consistency with request parameters.
            } else {
                String value = term.value().trim().toUpperCase(java.util.Locale.ROOT);
                if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(value)
                        || (severity != null && !severity.equals(value))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported log severity filter");
                }
                severity = value;
            }
        }
        Map<String, String> tags = StringUtils.hasText(tagKey) ? new LinkedHashMap<>(Map.of(tagKey, tagValue))
                : new LinkedHashMap<>();
        for (MonitorExploreQueryParser.Term term : parsed.terms()) {
            if ("tag".equals(term.field())) addExploreTag(tags, term.tagKey(), term.value());
            if ("user".equals(term.field())) {
                if (StringUtils.hasText(userId) && !userId.equals(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user filters must match");
                }
                userId = term.value();
            }
        }
        return new ExploreLogFilters(queryTraceId, parsed.text(), severity,
                queryEnvironment, queryRelease, userId, Map.copyOf(tags));
    }

    private boolean supportsExploreLogAggregation(
            String environment, String release, String traceId, String query, String groupBy) {
        if (!List.of("signal", "environment", "release", "level").contains(groupBy.toLowerCase())) return false;
        MonitorExploreQueryParser.Parsed parsed = MonitorExploreQueryParser.parse(query);
        if (parsed.expression() != null) return false;
        String queryTraceId = traceId;
        String queryEnvironment = environment;
        String queryRelease = release;
        String severity = null;
        for (MonitorExploreQueryParser.Term term : parsed.terms()) {
            if (term.negated() || !("trace".equals(term.field()) || "level".equals(term.field())
                    || "environment".equals(term.field()) || "release".equals(term.field())
                    || "user".equals(term.field()) || "tag".equals(term.field()))) return false;
            if ("trace".equals(term.field())) {
                if (StringUtils.hasText(queryTraceId) && !queryTraceId.equalsIgnoreCase(term.value())) return false;
                queryTraceId = term.value();
            } else if ("environment".equals(term.field())) {
                if (StringUtils.hasText(queryEnvironment) && !queryEnvironment.equals(term.value())) return false;
                queryEnvironment = term.value();
            } else if ("release".equals(term.field())) {
                if (StringUtils.hasText(queryRelease) && !queryRelease.equals(term.value())) return false;
                queryRelease = term.value();
            } else if ("level".equals(term.field())) {
                String value = term.value().trim().toUpperCase(java.util.Locale.ROOT);
                if (!List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL").contains(value)
                        || (severity != null && !severity.equals(value))) return false;
                severity = value;
            }
        }
        return true;
    }

    @PostMapping("/{projectKey}/explore/formula")
    public CommonResult<MonitorMetricFormulaResult> exploreMetricFormula(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorMetricFormulaRequest request) {
        MonitorProject project = adminService.requireProject(projectKey);
        if (StringUtils.hasText(request.getTagKey()) != StringUtils.hasText(request.getTagValue())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagKey and tagValue must be supplied together");
        }
        if (request.getMetricNames() == null || request.getMetricNames().stream().distinct().count() != request.getMetricNames().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formula metric names must be distinct");
        }
        try {
            MonitorMetricFormulaEvaluator.validate(request.getFormula(), request.getMetricNames().size());
            return CommonResult.success(queryService.exploreMetricFormula(project, request));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{projectKey}/explore/saved-queries")
    public CommonResult<List<MonitorSavedExploreQueryView>> savedExploreQueries(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(savedExploreQueryService.list(project));
    }

    @PostMapping("/{projectKey}/explore/saved-queries")
    public CommonResult<MonitorSavedExploreQueryView> createSavedExploreQuery(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorSavedExploreQueryRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        return CommonResult.success(savedExploreQueryService.create(project, request));
    }

    @PutMapping("/{projectKey}/explore/saved-queries/{id}")
    public CommonResult<MonitorSavedExploreQueryView> updateSavedExploreQuery(
            @PathVariable String projectKey,
            @PathVariable Long id,
            @Valid @RequestBody MonitorSavedExploreQueryRequest request) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        return CommonResult.success(savedExploreQueryService.update(project, id, request));
    }

    @DeleteMapping("/{projectKey}/explore/saved-queries/{id}")
    public CommonResult<Void> deleteSavedExploreQuery(@PathVariable String projectKey, @PathVariable Long id) {
        MonitorProject project = projectAccessService.requireProject(projectKey, true);
        savedExploreQueryService.delete(project, id);
        return CommonResult.success(null);
    }

    @GetMapping("/{projectKey}/issues/by-fingerprint")
    public CommonResult<MonitorIssue> issueByFingerprint(
            @PathVariable String projectKey,
            @RequestParam String fingerprint) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.issueByFingerprint(project.getId(), fingerprint));
    }

    @GetMapping("/{projectKey}/traces")
    public CommonResult<Object> traces(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.traces(project, hours, environment, release, pageNum, pageSize));
    }

    @GetMapping("/{projectKey}/traces/{traceId}/spans")
    public CommonResult<List<MonitorTraceSpan>> traceSpans(
            @PathVariable String projectKey,
            @PathVariable String traceId) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.traceSpans(project, traceId));
    }

    @GetMapping("/{projectKey}/issues")
    public CommonResult<Object> issues(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "720") int hours,
            @RequestParam(required = false) String release,
            @RequestParam(defaultValue = "lastSeen") String sort,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String environment) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                queryService.issues(project, pageNum, pageSize, status, hours, release, sort, query, environment)
        );
    }

    @GetMapping("/{projectKey}/issues/{issueId}")
    public CommonResult<Map<String, Object>> issue(
            @PathVariable String projectKey,
            @PathVariable Long issueId,
            @RequestParam(defaultValue = "20") int eventLimit) {
        MonitorProject project = adminService.requireProject(projectKey);
        MonitorIssue issue = queryService.issue(project.getId(), issueId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issue", issue);
        result.put("events", issue == null ? List.of() : queryService.issueEvents(project, issue, eventLimit));
        return CommonResult.success(result);
    }

    @PatchMapping("/{projectKey}/issues/{issueId}/status")
    public CommonResult<MonitorIssue> updateIssueStatus(
            @PathVariable String projectKey,
            @PathVariable Long issueId,
            @RequestParam String status) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(adminService.updateIssueStatus(project, issueId, status));
    }

    @PatchMapping("/{projectKey}/issues/status")
    public CommonResult<Integer> updateIssuesStatus(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorIssueBulkStatusRequest request) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(adminService.updateIssuesStatus(project, request));
    }

    @GetMapping("/{projectKey}/performance")
    public CommonResult<Map<String, Object>> performance(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                queryService.performance(project, hours, environment, release)
        );
    }

    @GetMapping("/{projectKey}/apis")
    public CommonResult<Map<String, Object>> apis(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                queryService.apiPerformance(project, hours, environment, release)
        );
    }

    @GetMapping("/{projectKey}/metrics")
    public CommonResult<Map<String, Object>> metrics(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release,
            @RequestParam(required = false) String name) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.metrics(project, hours, environment, release, name));
    }

    @GetMapping("/{projectKey}/releases")
    public CommonResult<List<MonitorRelease>> releases(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.releases(project.getId()));
    }

    @GetMapping("/{projectKey}/release-health")
    public CommonResult<List<com.macro.mall.tiny.modules.monitor.dto.MonitorReleaseHealth>> releaseHealth(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "168") int hours) {
        MonitorProject project = adminService.requireProject(projectKey);
        if (hours < 1 || hours > 24 * 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hours must be between 1 and 720");
        }
        return CommonResult.success(releaseHealthService.list(project.getProjectKey(), hours));
    }

    @GetMapping("/{projectKey}/data-scrubbing")
    public CommonResult<MonitorDataScrubbingSettings> dataScrubbingSettings(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(new MonitorDataScrubbingSettings(
                Boolean.TRUE.equals(project.getScrubEmails()),
                Boolean.TRUE.equals(project.getScrubCreditCards()),
                Boolean.TRUE.equals(project.getScrubIpAddresses()),
                Boolean.TRUE.equals(project.getScrubPhoneNumbers()),
                Boolean.TRUE.equals(project.getScrubChineseIdNumbers()),
                com.macro.mall.tiny.modules.monitor.service.MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields()),
                projectAccessService.canManageProject(project)));
    }

    @PutMapping("/{projectKey}/data-scrubbing")
    public CommonResult<MonitorDataScrubbingSettings> updateDataScrubbingSettings(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorDataScrubbingRequest request) {
        adminService.requireProjectOwner(projectKey);
        MonitorProject project = projectService.updateScrubbingSettings(projectKey, request);
        return CommonResult.success(new MonitorDataScrubbingSettings(
                Boolean.TRUE.equals(project.getScrubEmails()),
                Boolean.TRUE.equals(project.getScrubCreditCards()),
                Boolean.TRUE.equals(project.getScrubIpAddresses()),
                Boolean.TRUE.equals(project.getScrubPhoneNumbers()),
                Boolean.TRUE.equals(project.getScrubChineseIdNumbers()),
                com.macro.mall.tiny.modules.monitor.service.MonitorSensitiveFieldNames.fromStorage(project.getCustomSensitiveFields()),
                true));
    }

    @GetMapping("/{projectKey}/replays")
    public CommonResult<List<MonitorReplay>> replays(
            @PathVariable String projectKey,
            @RequestParam(required = false) String sessionId) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.replays(project.getId(), sessionId));
    }

    @GetMapping("/{projectKey}/replays/{replayId}")
    public CommonResult<JsonNode> replay(
            @PathVariable String projectKey,
            @PathVariable Long replayId) throws IOException {
        MonitorProject project = adminService.requireProject(projectKey);
        MonitorReplay replay = queryService.replay(project.getId(), replayId);
        if (replay == null) {
            return CommonResult.success(null);
        }
        return CommonResult.success(objectMapper.readTree(replayService.load(replay)));
    }

    @GetMapping("/{projectKey}/alerts/rules")
    public CommonResult<List<MonitorAlertRuleView>> alertRules(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        boolean canViewWebhookUrl = projectAccessService.canWriteProject(project);
        return CommonResult.success(queryService.alertRules(project.getId()).stream()
                .map(rule -> MonitorAlertRuleView.from(rule, canViewWebhookUrl)).toList());
    }

    @GetMapping("/{projectKey}/alerts/routes")
    public CommonResult<List<MonitorAlertNotificationRouteOption>> alertNotificationRoutes(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(notificationRouteService.listForProject(project));
    }

    @PostMapping("/{projectKey}/alerts/rules")
    public CommonResult<MonitorAlertRule> createAlertRule(
            @PathVariable String projectKey,
            @Valid @RequestBody AlertRuleRequest request) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(adminService.createRule(project, request));
    }

    @PutMapping("/{projectKey}/alerts/rules/{ruleId}")
    public CommonResult<MonitorAlertRule> updateAlertRule(
            @PathVariable String projectKey,
            @PathVariable Long ruleId,
            @Valid @RequestBody AlertRuleRequest request) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(adminService.updateRule(project, ruleId, request));
    }

    @GetMapping("/{projectKey}/alerts/records")
    public CommonResult<List<MonitorAlertRecord>> alertRecords(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.alertRecords(project.getId()));
    }

    @GetMapping("/{projectKey}/alerts/deliveries")
    public CommonResult<List<MonitorAlertDelivery>> alertDeliveries(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(deliveryService.list(project.getId()));
    }

    @GetMapping("/{projectKey}/alerts/silences")
    public CommonResult<List<MonitorAlertSilence>> alertSilences(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(silenceService.list(project));
    }

    @PostMapping("/{projectKey}/alerts/silences")
    public CommonResult<MonitorAlertSilence> createAlertSilence(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorAlertSilenceRequest request) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        return CommonResult.success(silenceService.create(project, request));
    }

    @DeleteMapping("/{projectKey}/alerts/silences/{silenceId}")
    public CommonResult<Void> deleteAlertSilence(
            @PathVariable String projectKey,
            @PathVariable String silenceId) {
        MonitorProject project = adminService.requireProject(projectKey, true);
        silenceService.delete(project, silenceId);
        return CommonResult.success(null);
    }

    @GetMapping("/{projectKey}/sourcemap/resolve")
    public CommonResult<SourceMapResolvedPosition> resolveSourceMap(
            @PathVariable String projectKey,
            @RequestParam String version,
            @RequestParam(defaultValue = "production") String environment,
            @RequestParam String bundleFile,
            @RequestParam int line,
            @RequestParam int column) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                sourceMapService.resolveForAdmin(
                        project, version, environment, bundleFile, line, column
                ).orElse(null)
        );
    }

    @PostMapping("/{projectKey}/sourcemap/resolve-stack")
    public CommonResult<List<SourceMapStackFrame>> resolveSourceMapStack(
            @PathVariable String projectKey,
            @Valid @RequestBody SourceMapStackResolveRequest request) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(sourceMapService.resolveStackForAdmin(
                project,
                request.version(),
                request.environment() == null ? "production" : request.environment(),
                request.stack(),
                request.file(),
                request.line(),
                request.column()
        ));
    }
}
