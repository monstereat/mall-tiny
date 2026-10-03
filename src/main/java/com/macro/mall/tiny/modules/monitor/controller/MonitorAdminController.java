package com.macro.mall.tiny.modules.monitor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.AlertRuleRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertSilenceRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueBulkStatusRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorAlertNotificationRouteOption;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @GetMapping("/{projectKey}/dashboard")
    public CommonResult<Map<String, Object>> dashboard(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.dashboard(project, hours, environment, release));
    }

    @GetMapping("/{projectKey}/logs")
    public CommonResult<com.macro.mall.tiny.modules.monitor.dto.MonitorLogSearchResult> logs(
            @PathVariable String projectKey,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "200") int limit) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(logQueryService.search(project, traceId, hours, limit, query));
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
        if ("logs".equalsIgnoreCase(type)) {
            if (parsedQuery.expression() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Logs Explore supports one trace filter at a time");
            }
            if (hasUserFilter || hasTagKey || hasTagValue) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Logs Explore supports trace:<id> and text filters only");
            }
            String queryTraceId = traceId;
            for (MonitorExploreQueryParser.Term term : parsedQuery.terms()) {
                if (!"trace".equals(term.field()) || term.negated()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Logs Explore supports trace:<id> and free-text filters only");
                }
                if (StringUtils.hasText(queryTraceId) && !queryTraceId.equalsIgnoreCase(term.value())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "traceId and trace:<id> filters must match");
                }
                queryTraceId = term.value();
            }
            int safeLimit = Math.max(1, Math.min(200, limit));
            int safeOffset = Math.max(0, Math.min(400, offset));
            var result = logQueryService.search(project, queryTraceId, hours,
                    Math.min(500, safeOffset + safeLimit + 1), parsedQuery.text());
            List<Map<String, Object>> events = result.entries().stream()
                    .skip(safeOffset)
                    .limit(safeLimit + 1L)
                    .map(entry -> {
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("signal_type", "logs");
                        event.put("event_id", entry.timestamp() + ":" + entry.line().hashCode());
                        event.put("event_time", entry.timestamp());
                        event.put("title", entry.line());
                        event.put("release", "");
                        event.put("environment", "");
                        event.put("page_url", "");
                        event.put("trace_id", result.traceId());
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
        if (hasTagKey != hasTagValue) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey and tagValue must be supplied together");
        }
        if (hasUserFilter && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (hasTagKey && (tagKey.length() > 64 || tagValue.length() > 128)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tagKey must be at most 64 characters and tagValue at most 128 characters");
        }
        return CommonResult.success(queryService.explore(
                project, hours, type, environment, release, traceId, query,
                hasUserFilter ? userId : null, hasTagKey ? tagKey : null, hasTagValue ? tagValue : null,
                limit, offset
        ));
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
        if ("logs".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Logs aggregation is not supported");
        }
        if (StringUtils.hasText(type) && !List.of("error", "performance", "behavior", "replay", "metric", "profile")
                .contains(type.trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported Explore signal type");
        }
        if (StringUtils.hasText(userId) && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (StringUtils.hasText(tagKey) != StringUtils.hasText(tagValue)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tagKey and tagValue must be supplied together");
        }
        if (StringUtils.hasText(tagKey) && (tagKey.length() > 64 || tagValue.length() > 128)) {
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
        } else if (List.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(normalizedAggregation)) {
            if (!"value".equalsIgnoreCase(field.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "numeric aggregations support the value field only");
            }
            if (!List.of("performance", "metric").contains(type == null ? "" : type.trim().toLowerCase())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "numeric aggregations require Performance or Metrics signal type");
            }
        }
        return CommonResult.success(queryService.exploreAggregate(project, hours, type, environment, release, traceId,
                query, userId, tagKey, tagValue, groupBy, aggregation, field));
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

    @GetMapping("/{projectKey}/issues")
    public CommonResult<Object> issues(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "720") int hours,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(
                queryService.issues(project.getId(), pageNum, pageSize, status, hours, release)
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
    public CommonResult<List<MonitorAlertRule>> alertRules(@PathVariable String projectKey) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(queryService.alertRules(project.getId()));
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
