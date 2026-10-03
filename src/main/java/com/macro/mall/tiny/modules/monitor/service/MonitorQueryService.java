package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.monitor.mapper.*;
import com.macro.mall.tiny.modules.monitor.model.*;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceOverview;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceSpan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Service
public class MonitorQueryService {

    public static final int EXPLORE_UNIQUE_USER_LIMIT = 10_000;
    private static final int ISSUE_FILTER_FINGERPRINT_LIMIT = 10_000;
    private static final int ISSUE_QUERY_MAX_LENGTH = 128;

    private record EventFilter(String clause, Object[] args) {
    }

    private final JdbcTemplate clickHouse;
    private final MonitorProjectMapper projectMapper;
    private final MonitorIssueMapper issueMapper;
    private final MonitorReleaseMapper releaseMapper;
    private final MonitorReplayMapper replayMapper;
    private final MonitorAlertRuleMapper alertRuleMapper;
    private final MonitorAlertRecordMapper alertRecordMapper;

    public MonitorQueryService(
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse,
            MonitorProjectMapper projectMapper,
            MonitorIssueMapper issueMapper,
            MonitorReleaseMapper releaseMapper,
            MonitorReplayMapper replayMapper,
            MonitorAlertRuleMapper alertRuleMapper,
            MonitorAlertRecordMapper alertRecordMapper) {
        this.clickHouse = clickHouse;
        this.projectMapper = projectMapper;
        this.issueMapper = issueMapper;
        this.releaseMapper = releaseMapper;
        this.replayMapper = replayMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.alertRecordMapper = alertRecordMapper;
    }

    public List<MonitorProject> projects() {
        return projectMapper.selectList(
                Wrappers.<MonitorProject>lambdaQuery()
                        .eq(MonitorProject::getStatus, 1)
                        .orderByDesc(MonitorProject::getId)
        );
    }

    public Map<String, Object> dashboard(
            MonitorProject project,
            int hours,
            String environment,
            String release) {
        EventFilter errorFilter = eventFilter(project, hours, environment, release);
        EventFilter performanceFilter = eventFilter(project, hours, environment, release);
        EventFilter behaviorFilter = eventFilter(project, hours, environment, release);
        Map<String, Object> result = new LinkedHashMap<>();

        Long errors = clickHouse.queryForObject(
                "SELECT count() FROM monitor.error_event" + errorFilter.clause(),
                Long.class,
                errorFilter.args()
        );
        Long affectedUsers = clickHouse.queryForObject(
                "SELECT uniqExactIf(user_id, user_id != '') FROM monitor.error_event" + errorFilter.clause(),
                Long.class,
                errorFilter.args()
        );
        Long apiEvents = clickHouse.queryForObject(
                "SELECT countIf(JSONExtractString(payload, 'data', 'category')='api') FROM monitor.behavior_event" +
                        behaviorFilter.clause(),
                Long.class,
                behaviorFilter.args()
        );

        var issueQuery = Wrappers.<MonitorIssue>lambdaQuery()
                .eq(MonitorIssue::getProjectId, project.getId())
                .eq(MonitorIssue::getStatus, "unresolved");
        if (StringUtils.hasText(release)) {
            issueQuery.eq(MonitorIssue::getLatestRelease, release);
        }
        Long unresolved = issueMapper.selectCount(issueQuery);

        result.put("errorCount", errors == null ? 0L : errors);
        result.put("affectedUsers", affectedUsers == null ? 0L : affectedUsers);
        result.put("apiEvents", apiEvents == null ? 0L : apiEvents);
        result.put("unresolvedIssues", unresolved == null ? 0L : unresolved);
        result.put("errorTrend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket, count() AS count FROM monitor.error_event" +
                        errorFilter.clause() + " GROUP BY bucket ORDER BY bucket",
                errorFilter.args()
        ));
        result.put("webVitals", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','metric') AS metric, " +
                        "avg(JSONExtractFloat(payload,'data','value')) AS value " +
                        "FROM monitor.performance_event" + performanceFilter.clause() +
                        " AND JSONExtractString(payload,'data','metric') IN ('FCP','LCP','CLS','TTFB','INP') " +
                        "GROUP BY metric ORDER BY metric",
                performanceFilter.args()
        ));
        return result;
    }

    public IPage<MonitorTraceOverview> traces(
            MonitorProject project,
            int hours,
            String environment,
            String release,
            long pageNum,
            long pageSize) {
        int safeHours = Math.max(1, Math.min(24 * 365, hours));
        long safePageNum = Math.max(1, pageNum);
        long safePageSize = Math.max(1, Math.min(100, pageSize));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        List<Object> args = new ArrayList<>();
        String union = traceEventUnion(project, since, environment, release, args);
        String grouped = "SELECT trace_id FROM (" + union + ") GROUP BY trace_id";
        Long total = clickHouse.queryForObject("SELECT count() FROM (" + grouped + ")", Long.class,
                args.toArray());

        List<Object> rowsArgs = new ArrayList<>();
        String rowsUnion = traceEventUnion(project, since, environment, release, rowsArgs);
        rowsArgs.add(safePageSize);
        rowsArgs.add((safePageNum - 1) * safePageSize);
        List<MonitorTraceOverview> records = clickHouse.queryForList(
                        "SELECT trace_id AS traceId,min(event_time) AS firstEventAt,max(event_time) AS lastEventAt," +
                                "count() AS eventCount,argMax(environment,event_time) AS environment," +
                                "argMax(release,event_time) AS release," +
                                "arrayStringConcat(arraySort(groupUniqArray(signal_type)), ',') AS signalTypes " +
                                "FROM (" + rowsUnion + ") GROUP BY trace_id " +
                                "ORDER BY lastEventAt DESC,traceId DESC LIMIT ? OFFSET ?",
                        rowsArgs.toArray())
                .stream()
                .map(row -> new MonitorTraceOverview(
                        String.valueOf(row.get("traceId")),
                        (Timestamp) row.get("firstEventAt"),
                        (Timestamp) row.get("lastEventAt"),
                        ((Number) row.get("eventCount")).longValue(),
                        (String) row.get("environment"),
                        (String) row.get("release"),
                        (String) row.get("signalTypes")))
                .toList();

        Page<MonitorTraceOverview> page = new Page<>(safePageNum, safePageSize);
        page.setTotal(total == null ? 0 : total);
        page.setRecords(records);
        return page;
    }

    public List<MonitorTraceSpan> traceSpans(MonitorProject project, String traceId) {
        return clickHouse.queryForList(
                        "SELECT trace_id AS traceId,JSONExtractString(payload,'data','spanId') AS spanId," +
                                "JSONExtractString(payload,'data','parentSpanId') AS parentSpanId," +
                                "JSONExtractString(payload,'data','op') AS op," +
                                "JSONExtractString(payload,'data','serviceName') AS serviceName," +
                                "JSONExtractString(payload,'data','description') AS description," +
                                "JSONExtractUInt(payload,'data','startTime') AS startTime," +
                                "JSONExtractFloat(payload,'data','durationMs') AS durationMs," +
                                "JSONExtractString(payload,'data','status') AS status," +
                                "if(JSONHas(payload,'data','statusCode'),JSONExtractInt(payload,'data','statusCode')," +
                        "CAST(NULL AS Nullable(Int64))) AS statusCode " +
                                "FROM monitor.span_event WHERE project_id=? AND trace_id=? " +
                                "ORDER BY startTime,spanId LIMIT 2000",
                        project.getProjectKey(), traceId)
                .stream()
                .map(row -> new MonitorTraceSpan(
                        String.valueOf(row.get("traceId")),
                        String.valueOf(row.get("spanId")),
                        Objects.toString(row.get("parentSpanId"), ""),
                        Objects.toString(row.get("op"), "").startsWith("otel.") ? "server" : "browser",
                        Objects.toString(row.get("serviceName"), ""),
                        otelSpanKind(Objects.toString(row.get("op"), "")),
                        Objects.toString(row.get("op"), ""),
                        Objects.toString(row.get("description"), ""),
                        ((Number) row.get("startTime")).longValue(),
                        ((Number) row.get("durationMs")).doubleValue(),
                        Objects.toString(row.get("status"), ""),
                        row.get("statusCode") instanceof Number code ? code.longValue() : null))
                .toList();
    }

    private String otelSpanKind(String op) {
        return op.startsWith("otel.") ? op.substring("otel.".length()) : "";
    }

    private String traceEventUnion(
            MonitorProject project,
            Timestamp since,
            String environment,
            String release,
            List<Object> args) {
        Map<String, String> tables = Map.of(
                "error", "monitor.error_event",
                "performance", "monitor.performance_event",
                "behavior", "monitor.behavior_event",
                "replay", "monitor.replay_event",
                "metric", "monitor.metric_event",
                "profile", "monitor.profile_event",
                "span", "monitor.span_event");
        List<String> selects = new ArrayList<>();
        for (Map.Entry<String, String> table : tables.entrySet()) {
            StringBuilder where = new StringBuilder(" WHERE project_id=? AND trace_id!='' AND event_time>=?");
            args.add(project.getProjectKey());
            args.add(since);
            if (StringUtils.hasText(environment)) {
                where.append(" AND environment=?");
                args.add(environment);
            }
            if (StringUtils.hasText(release)) {
                where.append(" AND release=?");
                args.add(release);
            }
            selects.add("SELECT trace_id,event_time,environment,release,'" + table.getKey() +
                    "' AS signal_type FROM " + table.getValue() + where);
        }
        return String.join(" UNION ALL ", selects);
    }

    public IPage<MonitorIssue> issues(
            Long projectId,
            long pageNum,
            long pageSize,
            String status,
            int hours,
            String release) {
        return issues(projectId, pageNum, pageSize, status, hours, release, null);
    }

    public IPage<MonitorIssue> issues(
            Long projectId,
            long pageNum,
            long pageSize,
            String status,
            int hours,
            String release,
            String sort) {
        return issues(projectId, null, pageNum, pageSize, status, hours, release, sort, null, null);
    }

    public IPage<MonitorIssue> issues(
            MonitorProject project,
            long pageNum,
            long pageSize,
            String status,
            int hours,
            String release,
            String sort,
            String query,
            String environment) {
        return issues(project.getId(), project.getProjectKey(), pageNum, pageSize, status, hours,
                release, sort, query, environment);
    }

    private IPage<MonitorIssue> issues(
            Long projectId,
            String projectKey,
            long pageNum,
            long pageSize,
            String status,
            int hours,
            String release,
            String sort,
            String queryString,
            String environment) {
        int safeHours = Math.max(1, Math.min(24 * 365, hours));
        Date since = Date.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        String keyword = normalizeIssueQuery(queryString);
        String normalizedEnvironment = normalizeIssueEnvironment(environment);

        LambdaQueryWrapper<MonitorIssue> query = Wrappers.<MonitorIssue>lambdaQuery()
                .eq(MonitorIssue::getProjectId, projectId)
                .ge(MonitorIssue::getLastSeen, since);
        if (normalizedEnvironment != null) {
            List<String> fingerprints = issueFingerprints(
                    projectKey, since, normalizedEnvironment, release, null);
            if (fingerprints.isEmpty()) return new Page<>(pageNum, pageSize, 0);
            query.in(MonitorIssue::getFingerprint, fingerprints);
        }
        if (keyword != null) {
            List<String> messageFingerprints = issueFingerprints(
                    projectKey, since, normalizedEnvironment, release, keyword);
            query.and(filter -> {
                filter.like(MonitorIssue::getTitle, keyword)
                        .or().like(MonitorIssue::getFingerprint, keyword);
                if (!messageFingerprints.isEmpty()) {
                    filter.or().in(MonitorIssue::getFingerprint, messageFingerprints);
                }
            });
        }
        applyIssueSort(query, sort);
        if (StringUtils.hasText(status)) query.eq(MonitorIssue::getStatus, status);
        if (StringUtils.hasText(release)) query.eq(MonitorIssue::getLatestRelease, release);
        IPage<MonitorIssue> page = issueMapper.selectPage(Page.of(pageNum, pageSize), query);
        enrichIssueTriage(projectId, page.getRecords(), release);
        return page;
    }

    private String normalizeIssueQuery(String query) {
        if (!StringUtils.hasText(query)) return null;
        String normalized = query.trim();
        if (normalized.length() > ISSUE_QUERY_MAX_LENGTH) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Issue search query must be at most 128 characters");
        }
        return normalized;
    }

    private String normalizeIssueEnvironment(String environment) {
        if (!StringUtils.hasText(environment)) return null;
        String normalized = environment.trim();
        if (normalized.length() > ISSUE_QUERY_MAX_LENGTH) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Issue environment must be at most 128 characters");
        }
        return normalized;
    }

    private List<String> issueFingerprints(
            String projectKey, Date since, String environment, String release, String messageQuery) {
        StringBuilder sql = new StringBuilder("SELECT DISTINCT fingerprint FROM monitor.error_event " +
                "WHERE project_id=? AND event_time>=? AND fingerprint!=''");
        List<Object> args = new ArrayList<>(List.of(projectKey, new Timestamp(since.getTime())));
        if (StringUtils.hasText(environment)) {
            sql.append(" AND environment=?");
            args.add(environment);
        }
        if (StringUtils.hasText(release)) {
            sql.append(" AND release=?");
            args.add(release);
        }
        if (StringUtils.hasText(messageQuery)) {
            sql.append(" AND (positionCaseInsensitiveUTF8(JSONExtractString(payload,'data','message'),?)>0 " +
                    "OR positionCaseInsensitiveUTF8(JSONExtractString(payload,'data','name'),?)>0)");
            args.add(messageQuery);
            args.add(messageQuery);
        }
        sql.append(" LIMIT ?");
        args.add(ISSUE_FILTER_FINGERPRINT_LIMIT + 1);
        List<String> fingerprints = clickHouse.queryForList(sql.toString(), String.class, args.toArray());
        if (fingerprints.size() > ISSUE_FILTER_FINGERPRINT_LIMIT) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Issue filter matched more than 10,000 fingerprints; narrow the time range or filter");
        }
        return fingerprints;
    }

    private void applyIssueSort(LambdaQueryWrapper<MonitorIssue> query, String sort) {
        if ("eventCount".equals(sort)) {
            query.orderByDesc(MonitorIssue::getEventCount);
        } else if ("affectedUsers".equals(sort)) {
            query.orderByDesc(MonitorIssue::getAffectedUsers);
        }
        query.orderByDesc(MonitorIssue::getLastSeen)
                .orderByDesc(MonitorIssue::getId);
    }

    private void enrichIssueTriage(Long projectId, List<MonitorIssue> issues, String release) {
        if (issues.isEmpty()) return;
        Instant now = Instant.now();
        Instant currentStart = now.minus(24, ChronoUnit.HOURS);
        Instant previousStart = now.minus(48, ChronoUnit.HOURS);
        Date newIssueCutoff = Date.from(currentStart);
        issues.forEach(issue -> {
            issue.setNewIssue(issue.getCreateTime() != null && !issue.getCreateTime().before(newIssueCutoff));
            issue.setEventsLast24h(0);
            issue.setEventsPrevious24h(0);
        });

        MonitorProject project = projectMapper.selectById(projectId);
        if (project == null || !StringUtils.hasText(project.getProjectKey())) return;
        List<String> fingerprints = issues.stream().map(MonitorIssue::getFingerprint).distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(fingerprints.size(), "?"));
        StringBuilder sql = new StringBuilder("SELECT fingerprint, countIf(event_time >= ?) AS current_count, " +
                "countIf(event_time < ? AND event_time >= ?) AS previous_count " +
                "FROM monitor.error_event WHERE project_id=? AND fingerprint IN (").append(placeholders)
                .append(") AND event_time >= ? ");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(currentStart));
        args.add(Timestamp.from(currentStart));
        args.add(Timestamp.from(previousStart));
        args.add(project.getProjectKey());
        args.addAll(fingerprints);
        args.add(Timestamp.from(previousStart));
        if (StringUtils.hasText(release)) {
            sql.append("AND release=? ");
            args.add(release);
        }
        sql.append("GROUP BY fingerprint");

        Map<String, Map<String, Object>> countsByFingerprint = new HashMap<>();
        for (Map<String, Object> row : clickHouse.queryForList(sql.toString(), args.toArray())) {
            countsByFingerprint.put(String.valueOf(row.get("fingerprint")), row);
        }
        for (MonitorIssue issue : issues) {
            Map<String, Object> counts = countsByFingerprint.get(issue.getFingerprint());
            if (counts == null) continue;
            issue.setEventsLast24h(number(counts.get("current_count")));
            issue.setEventsPrevious24h(number(counts.get("previous_count")));
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public MonitorIssue issue(Long projectId, Long issueId) {
        return issueMapper.selectOne(
                Wrappers.<MonitorIssue>lambdaQuery()
                        .eq(MonitorIssue::getProjectId, projectId)
                        .eq(MonitorIssue::getId, issueId)
                        .last("LIMIT 1")
        );
    }

    public List<Map<String, Object>> issueEvents(MonitorProject project, MonitorIssue issue, int limit) {
        return clickHouse.queryForList(
                "SELECT event_id,event_time,session_id,user_id,release,environment,page_url,trace_id,payload " +
                        "FROM monitor.error_event WHERE project_id=? AND fingerprint=? ORDER BY event_time DESC LIMIT ?",
                project.getProjectKey(), issue.getFingerprint(), Math.max(1, Math.min(100, limit))
        );
    }

    public Map<String, Object> performance(
            MonitorProject project,
            int hours,
            String environment,
            String release) {
        EventFilter filter = eventFilter(project, hours, environment, release);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','metric') AS metric, " +
                        "avg(JSONExtractFloat(payload,'data','value')) AS avgValue, " +
                        "quantile(0.75)(JSONExtractFloat(payload,'data','value')) AS p75, " +
                        "quantile(0.95)(JSONExtractFloat(payload,'data','value')) AS p95, count() AS samples " +
                        "FROM monitor.performance_event" + filter.clause() +
                        " GROUP BY metric ORDER BY metric",
                filter.args()
        ));
        result.put("trend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket, JSONExtractString(payload,'data','metric') AS metric, " +
                        "avg(JSONExtractFloat(payload,'data','value')) AS value " +
                        "FROM monitor.performance_event" + filter.clause() +
                        " GROUP BY bucket,metric ORDER BY bucket,metric",
                filter.args()
        ));
        result.put("recent", clickHouse.queryForList(
                "SELECT event_time,page_url,release,environment,JSONExtractString(payload,'data','metric') AS metric," +
                        "JSONExtractFloat(payload,'data','value') AS value FROM monitor.performance_event" +
                        filter.clause() + " ORDER BY event_time DESC LIMIT 200",
                filter.args()
        ));
        return result;
    }

    public Map<String, Object> apiPerformance(
            MonitorProject project,
            int hours,
            String environment,
            String release) {
        EventFilter filter = eventFilter(project, hours, environment, release);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','url') AS url, count() AS requests, " +
                        "avg(JSONExtractFloat(payload,'data','duration')) AS avgRt, " +
                        "quantile(0.95)(JSONExtractFloat(payload,'data','duration')) AS p95, " +
                        "countIf(JSONExtractInt(payload,'data','status')>=400) AS failures " +
                        "FROM monitor.behavior_event" + filter.clause() +
                        " AND JSONExtractString(payload,'data','category')='api' " +
                        "GROUP BY url ORDER BY requests DESC LIMIT 100",
                filter.args()
        ));
        result.put("trend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket, count() AS requests, " +
                        "countIf(JSONExtractInt(payload,'data','status')>=400) AS failures, " +
                        "avg(JSONExtractFloat(payload,'data','duration')) AS avgRt " +
                        "FROM monitor.behavior_event" + filter.clause() +
                        " AND JSONExtractString(payload,'data','category')='api' " +
                        "GROUP BY bucket ORDER BY bucket",
                filter.args()
        ));
        return result;
    }

    public Map<String, Object> metrics(
            MonitorProject project,
            int hours,
            String environment,
            String release,
            String name) {
        EventFilter filter = eventFilter(project, hours, environment, release);
        String nameFilter = StringUtils.hasText(name) ? " AND JSONExtractString(payload,'data','name')=?" : "";
        Object[] args = filter.args();
        if (StringUtils.hasText(name)) {
            args = java.util.Arrays.copyOf(args, args.length + 1);
            args[args.length - 1] = name.trim();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','name') AS name," +
                        "JSONExtractString(payload,'data','metricType') AS metricType," +
                        "JSONExtractString(payload,'data','unit') AS unit,count() AS samples," +
                        "sum(JSONExtractFloat(payload,'data','value')) AS sum," +
                        "avg(JSONExtractFloat(payload,'data','value')) AS avg," +
                        "quantile(0.50)(JSONExtractFloat(payload,'data','value')) AS p50," +
                        "quantile(0.95)(JSONExtractFloat(payload,'data','value')) AS p95," +
                        "min(JSONExtractFloat(payload,'data','value')) AS min," +
                        "max(JSONExtractFloat(payload,'data','value')) AS max " +
                        "FROM monitor.metric_event" + filter.clause() + nameFilter +
                        " GROUP BY name,metricType,unit ORDER BY samples DESC LIMIT 200",
                args
        ));
        result.put("trend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket,JSONExtractString(payload,'data','name') AS name," +
                        "JSONExtractString(payload,'data','metricType') AS metricType," +
                        "if(metricType='counter',sum(JSONExtractFloat(payload,'data','value'))," +
                        "avg(JSONExtractFloat(payload,'data','value'))) AS value,count() AS samples " +
                        "FROM monitor.metric_event" + filter.clause() + nameFilter +
                        " GROUP BY bucket,name,metricType ORDER BY bucket,name,metricType",
                args
        ));
        result.put("recent", clickHouse.queryForList(
                "SELECT event_time,JSONExtractString(payload,'data','name') AS name," +
                        "JSONExtractString(payload,'data','metricType') AS metricType," +
                        "JSONExtractFloat(payload,'data','value') AS value," +
                        "JSONExtractString(payload,'data','unit') AS unit," +
                        "JSONExtractRaw(payload,'data','tags') AS tags,trace_id " +
                        "FROM monitor.metric_event" + filter.clause() + nameFilter +
                        " ORDER BY event_time DESC LIMIT 200",
                args
        ));
        return result;
    }

    public MonitorExploreResult explore(
            MonitorProject project,
            int hours,
            String signalType,
            String environment,
            String release,
            String traceId,
            String query,
            int limit,
            int offset) {
        return explore(project, hours, signalType, environment, release, traceId, query,
                null, null, null, limit, offset);
    }

    public MonitorExploreResult explore(
            MonitorProject project,
            int hours,
            String signalType,
            String environment,
            String release,
            String traceId,
            String query,
            String userId,
            String tagKey,
            String tagValue,
            int limit,
            int offset) {
        int safeHours = Math.max(1, Math.min(24 * 90, hours));
        int safeLimit = Math.max(1, Math.min(500, limit));
        int safeOffset = Math.max(0, Math.min(10000, offset));
        MonitorExploreQueryParser.Parsed parsedQuery = MonitorExploreQueryParser.parse(query);
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        Map<String, String> tables = Map.of(
                "error", "monitor.error_event",
                "performance", "monitor.performance_event",
                "behavior", "monitor.behavior_event",
                "replay", "monitor.replay_event",
                "metric", "monitor.metric_event",
                "profile", "monitor.profile_event"
        );
        List<String> selectedTables = StringUtils.hasText(signalType)
                ? List.of(tables.getOrDefault(signalType.toLowerCase(), ""))
                : List.of(tables.get("error"), tables.get("performance"), tables.get("behavior"),
                        tables.get("replay"), tables.get("metric"), tables.get("profile"));
        if (selectedTables.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("unsupported signal type");
        }

        List<Object> args = new ArrayList<>();
        List<String> selects = new ArrayList<>();
        for (String table : selectedTables) {
            String type = tables.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(table))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
            StringBuilder where = new StringBuilder(" WHERE project_id=? AND event_time>=?");
            List<Object> tableArgs = new ArrayList<>(List.of(project.getProjectKey(), since));
            appendExploreFilters(where, tableArgs, environment, release, traceId, parsedQuery, userId, tagKey, tagValue);
            selects.add("SELECT '" + type + "' AS signal_type,event_id,event_time," +
                    "coalesce(nullIf(JSONExtractString(payload,'data','message'),'')," +
                    "nullIf(JSONExtractString(payload,'data','error','message'),'')," +
                    "nullIf(JSONExtractString(payload,'data','metric'),'')," +
                    "nullIf(JSONExtractString(payload,'data','category'),'')," +
                    "nullIf(JSONExtractString(payload,'data','name'),'')," +
                    "nullIf(JSONExtractString(payload,'data','url'),'')," +
                    "nullIf(JSONExtractString(payload,'data','name'),''),page_url,'" + type + "') AS title," +
                    "release,environment,page_url,trace_id,fingerprint,session_id,substring(payload,1,4000) AS payload " +
                    "FROM " + table + where);
            args.addAll(tableArgs);
        }
        args.add(safeLimit + 1);
        args.add(safeOffset);
        List<Map<String, Object>> rows = clickHouse.queryForList(
                String.join(" UNION ALL ", selects) + " ORDER BY event_time DESC LIMIT ? OFFSET ?",
                args.toArray()
        );
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, safeLimit));
        }
        return new MonitorExploreResult(rows, hasMore, safeLimit);
    }

    public MonitorExploreAggregationResult exploreAggregate(
            MonitorProject project,
            int hours,
            String signalType,
            String environment,
            String release,
            String traceId,
            String query,
            String userId,
            String tagKey,
            String tagValue,
            String groupBy,
            String aggregation,
            String aggregateField) {
        String normalizedGroupBy = normalizeExploreGroupBy(groupBy);
        String normalizedAggregation = normalizeExploreAggregation(aggregation);
        String normalizedField = normalizeExploreAggregateField(aggregateField, normalizedAggregation);
        int safeHours = Math.max(1, Math.min(24 * 90, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        MonitorExploreQueryParser.Parsed parsedQuery = MonitorExploreQueryParser.parse(query);
        Map<String, String> tables = Map.of(
                "error", "monitor.error_event",
                "performance", "monitor.performance_event",
                "behavior", "monitor.behavior_event",
                "replay", "monitor.replay_event",
                "metric", "monitor.metric_event",
                "profile", "monitor.profile_event"
        );
        List<String> selectedTables = StringUtils.hasText(signalType)
                ? List.of(tables.getOrDefault(signalType.toLowerCase(), ""))
                : List.of(tables.get("error"), tables.get("performance"), tables.get("behavior"),
                        tables.get("replay"), tables.get("metric"), tables.get("profile"));
        if (selectedTables.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("unsupported signal type");
        }
        if ("logs".equalsIgnoreCase(signalType)) {
            throw new IllegalArgumentException("Logs aggregation is not supported");
        }
        if (Set.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(normalizedAggregation)
                && !("performance".equalsIgnoreCase(signalType) || "metric".equalsIgnoreCase(signalType))) {
            throw new IllegalArgumentException("numeric aggregations require Performance or Metrics signal type");
        }

        List<Object> args = new ArrayList<>();
        List<String> selects = new ArrayList<>();
        for (String table : selectedTables) {
            String type = tables.entrySet().stream().filter(entry -> entry.getValue().equals(table))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            String groupExpression = switch (normalizedGroupBy) {
                case "signal" -> "'" + type + "'";
                case "environment" -> "environment";
                case "release" -> "release";
                case "url" -> "page_url";
                case "level" -> "JSONExtractString(payload,'data','level')";
                default -> "JSONExtractString(payload,'data','tags',?)";
            };
            String aggregateExpression = switch (normalizedAggregation) {
                case "count_unique" -> switch (normalizedField) {
                    case "user" -> "user_id";
                    case "event" -> "event_id";
                    case "trace" -> "trace_id";
                    case "url" -> "page_url";
                    default -> "JSONExtractString(payload,'data','tags',?)";
                };
                case "sum", "avg", "min", "max", "p50", "p75", "p95" -> "JSONExtractFloat(payload,'data','value')";
                default -> "toFloat64(0)";
            };
            StringBuilder where = new StringBuilder(" WHERE project_id=? AND event_time>=?");
            List<Object> tableArgs = new ArrayList<>();
            if (normalizedGroupBy.startsWith("tag.")) tableArgs.add(normalizedGroupBy.substring(4));
            if ("count_unique".equals(normalizedAggregation) && normalizedField.startsWith("tag.")) {
                tableArgs.add(normalizedField.substring(4));
            }
            tableArgs.add(project.getProjectKey());
            tableArgs.add(since);
            if (Set.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(normalizedAggregation)) {
                where.append(" AND JSONHas(payload,'data','value')");
            }
            appendExploreFilters(where, tableArgs, environment, release, traceId, parsedQuery, userId, tagKey, tagValue);
            selects.add("SELECT coalesce(nullIf(" + groupExpression + ",''),'(empty)') AS group_value," +
                    aggregateExpression + " AS aggregate_value FROM " + table + where);
            args.addAll(tableArgs);
        }
        String aggregateSql = switch (normalizedAggregation) {
            case "count" -> "toFloat64(0)";
            case "count_unique" -> "toFloat64(uniqExactIf(aggregate_value,aggregate_value!=''))";
            case "sum" -> "sum(aggregate_value)";
            case "avg" -> "avg(aggregate_value)";
            case "min" -> "min(aggregate_value)";
            case "max" -> "max(aggregate_value)";
            case "p50" -> "quantile(0.50)(aggregate_value)";
            case "p75" -> "quantile(0.75)(aggregate_value)";
            case "p95" -> "quantile(0.95)(aggregate_value)";
            default -> throw new IllegalStateException("unsupported Explore aggregation");
        };
        String orderBy = "count".equals(normalizedAggregation) ? "count" : "aggregate_value";
        String sql = "SELECT group_value AS value,count() AS count," + aggregateSql + " AS aggregate_value FROM (" +
                String.join(" UNION ALL ", selects) + ") GROUP BY group_value ORDER BY " + orderBy +
                " DESC,value ASC LIMIT 100";
        List<MonitorExploreAggregationResult.Bucket> buckets = clickHouse.queryForList(sql, args.toArray()).stream()
                .map(row -> new MonitorExploreAggregationResult.Bucket(
                        String.valueOf(row.get("value")), ((Number) row.get("count")).longValue(),
                        "count".equals(normalizedAggregation) ? null : ((Number) row.get("aggregate_value")).doubleValue()))
                .toList();
        return new MonitorExploreAggregationResult(normalizedGroupBy, normalizedAggregation, normalizedField, buckets);
    }

    public Map<String, Set<String>> exploreUniqueUsersBySignal(
            MonitorProject project, int hours, String environment, String release, String traceId, String query,
            String userId, String tagKey, String tagValue) {
        int safeHours = Math.max(1, Math.min(168, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        MonitorExploreQueryParser.Parsed parsedQuery = MonitorExploreQueryParser.parse(query);
        Map<String, String> tables = Map.of(
                "error", "monitor.error_event",
                "performance", "monitor.performance_event",
                "behavior", "monitor.behavior_event",
                "replay", "monitor.replay_event",
                "metric", "monitor.metric_event",
                "profile", "monitor.profile_event");
        List<String> selects = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, String> entry : tables.entrySet()) {
            StringBuilder where = new StringBuilder(" WHERE project_id=? AND event_time>=? AND user_id!=''");
            List<Object> tableArgs = new ArrayList<>(List.of(project.getProjectKey(), since));
            appendExploreFilters(where, tableArgs, environment, release, traceId, parsedQuery, userId, tagKey, tagValue);
            selects.add("SELECT '" + entry.getKey() + "' AS signal,user_id FROM " + entry.getValue() + where);
            args.addAll(tableArgs);
        }
        String sql = "SELECT signal,user_id FROM (" + String.join(" UNION ALL ", selects) +
                ") GROUP BY signal,user_id LIMIT ?";
        args.add(EXPLORE_UNIQUE_USER_LIMIT + 1);
        List<Map<String, Object>> rows = clickHouse.queryForList(sql, args.toArray());
        if (rows.size() > EXPLORE_UNIQUE_USER_LIMIT) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "mixed Explore unique-user result exceeds the 10,000 signal-user limit; narrow the time range or filters");
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String signal = String.valueOf(row.get("signal"));
            Object rawUserId = row.get("user_id");
            if (rawUserId != null && StringUtils.hasText(String.valueOf(rawUserId))) {
                result.computeIfAbsent(signal, ignored -> new java.util.HashSet<>()).add(String.valueOf(rawUserId));
            }
        }
        return result;
    }

    public MonitorMetricFormulaResult exploreMetricFormula(MonitorProject project, MonitorMetricFormulaRequest request) {
        List<String> metricNames = request.getMetricNames().stream().map(String::trim).distinct().toList();
        if (metricNames.size() < 2 || metricNames.size() > 5 || metricNames.size() != request.getMetricNames().size()) {
            throw new IllegalArgumentException("formula requires 2 to 5 distinct metric names");
        }
        String formula = request.getFormula().trim();
        MonitorMetricFormulaEvaluator.validate(formula, metricNames.size());
        Timestamp since = Timestamp.from(Instant.now().minus(Math.max(1, Math.min(24 * 90, request.getHours())), ChronoUnit.HOURS));
        StringBuilder where = new StringBuilder(" WHERE project_id=? AND event_time>=? AND JSONHas(payload,'data','value')");
        List<Object> args = new ArrayList<>();
        args.add(project.getProjectKey());
        args.add(since);
        where.append(" AND JSONExtractString(payload,'data','name') IN (")
                .append(String.join(",", java.util.Collections.nCopies(metricNames.size(), "?")))
                .append(')');
        args.addAll(metricNames);
        MonitorExploreQueryParser.Parsed parsedQuery = MonitorExploreQueryParser.parse(request.getQuery());
        appendExploreFilters(where, args, request.getEnvironment(), request.getRelease(), request.getTraceId(),
                parsedQuery, request.getUserId(), request.getTagKey(), request.getTagValue());

        String metricName = "JSONExtractString(payload,'data','name')";
        String metricType = "JSONExtractString(payload,'data','metricType')";
        String value = "JSONExtractFloat(payload,'data','value')";
        String sql = "SELECT toStartOfHour(event_time) AS bucket," + metricName + " AS name," + metricType + " AS metricType," +
                "if(" + metricType + "='counter',sum(" + value + "),avg(" + value + ")) AS value FROM monitor.metric_event" + where +
                " GROUP BY bucket,name,metricType ORDER BY bucket,name";
        Map<String, Map<Character, Double>> valuesByBucket = new TreeMap<>();
        for (Map<String, Object> row : clickHouse.queryForList(sql, args.toArray())) {
            String name = String.valueOf(row.get("name"));
            int metricIndex = metricNames.indexOf(name);
            if (metricIndex < 0) continue;
            Map<Character, Double> values = valuesByBucket.computeIfAbsent(String.valueOf(row.get("bucket")), ignored -> new LinkedHashMap<>());
            char alias = (char) ('a' + metricIndex);
            if (values.putIfAbsent(alias, ((Number) row.get("value")).doubleValue()) != null) {
                throw new IllegalArgumentException("formula metric changes type within a time bucket");
            }
        }
        List<MonitorMetricFormulaResult.Point> points = valuesByBucket.entrySet().stream()
                .filter(entry -> entry.getValue().size() == metricNames.size())
                .map(entry -> {
                    Double result = MonitorMetricFormulaEvaluator.evaluate(formula, metricNames.size(), entry.getValue());
                    return result == null ? null : new MonitorMetricFormulaResult.Point(entry.getKey(), result);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new MonitorMetricFormulaResult(formula, points);
    }

    private String normalizeExploreAggregation(String aggregation) {
        String normalized = StringUtils.hasText(aggregation) ? aggregation.trim().toLowerCase(Locale.ROOT) : "count";
        if (Set.of("count", "count_unique", "sum", "avg", "min", "max", "p50", "p75", "p95").contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("unsupported Explore aggregation");
    }

    private String normalizeExploreAggregateField(String field, String aggregation) {
        String raw = StringUtils.hasText(field) ? field.trim() : "value";
        String normalized = raw.toLowerCase(Locale.ROOT);
        if ("count_unique".equals(aggregation)) {
            if (Set.of("user", "event", "trace", "url").contains(normalized)) return normalized;
            if (normalized.startsWith("tag.") && raw.substring(4).matches("[A-Za-z0-9_.-]{1,64}")) return "tag." + raw.substring(4);
            throw new IllegalArgumentException("unsupported Explore unique-count field");
        }
        if (Set.of("sum", "avg", "min", "max", "p50", "p75", "p95").contains(aggregation)
                && !"value".equals(normalized)) {
            throw new IllegalArgumentException("numeric aggregations support the value field only");
        }
        return "value";
    }

    private String normalizeExploreGroupBy(String groupBy) {
        String raw = StringUtils.hasText(groupBy) ? groupBy.trim() : "signal";
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (Set.of("signal", "environment", "release", "url", "level").contains(normalized)) return normalized;
        if (normalized.startsWith("tag.") && raw.substring(4).matches("[A-Za-z0-9_.-]{1,64}")) return "tag." + raw.substring(4);
        throw new IllegalArgumentException("unsupported Explore aggregation dimension");
    }

    private void appendExploreFilters(
            StringBuilder where,
            List<Object> args,
            String environment,
            String release,
            String traceId,
            MonitorExploreQueryParser.Parsed parsedQuery,
            String userId,
            String tagKey,
            String tagValue) {
        if (StringUtils.hasText(environment)) { where.append(" AND environment=?"); args.add(environment); }
        if (StringUtils.hasText(release)) { where.append(" AND release=?"); args.add(release); }
        if (StringUtils.hasText(traceId)) { where.append(" AND trace_id=?"); args.add(traceId.trim()); }
        if (StringUtils.hasText(parsedQuery.text())) {
            where.append(" AND positionCaseInsensitive(concat(event_id,' ',page_url,' ',trace_id,' ',payload),?)>0");
            args.add(parsedQuery.text());
        }
        if (StringUtils.hasText(userId)) { where.append(" AND user_id=?"); args.add(userId); }
        if (StringUtils.hasText(tagKey) && StringUtils.hasText(tagValue)) {
            where.append(" AND (JSONExtractRaw(payload,'data','tags',?)=? OR JSONExtractString(payload,'data','tags',?)=?)");
            args.add(tagKey);
            args.add(tagValue);
            args.add(tagKey);
            args.add(tagValue);
        }
        if (parsedQuery.expression() != null) {
            where.append(" AND ");
            appendExploreExpression(parsedQuery.expression(), where, args);
        } else {
            for (MonitorExploreQueryParser.Term term : parsedQuery.terms()) {
                where.append(" AND ");
                appendExploreFilter(term, where, args);
            }
        }
    }

    private void appendExploreExpression(MonitorExploreQueryParser.Node node, StringBuilder sql, List<Object> args) {
        if (node instanceof MonitorExploreQueryParser.Filter filter) {
            appendExploreFilter(filter.term(), sql, args);
            return;
        }
        MonitorExploreQueryParser.Junction junction = (MonitorExploreQueryParser.Junction) node;
        sql.append('(');
        String separator = junction.or() ? " OR " : " AND ";
        for (int i = 0; i < junction.children().size(); i++) {
            if (i > 0) sql.append(separator);
            appendExploreExpression(junction.children().get(i), sql, args);
        }
        sql.append(')');
    }

    private void appendExploreFilter(MonitorExploreQueryParser.Term term, StringBuilder sql, List<Object> args) {
        String expression = switch (term.field()) {
            case "environment" -> "environment=?";
            case "release" -> "release=?";
            case "trace" -> "trace_id=?";
            case "user" -> "user_id=?";
            case "event" -> "event_id=?";
            case "url" -> "positionCaseInsensitive(page_url,?)>0";
            case "level" -> "JSONExtractString(payload,'data','level')=?";
            case "tag" -> "(JSONExtractRaw(payload,'data','tags',?)=? OR " +
                    "JSONExtractString(payload,'data','tags',?)=?)";
            default -> throw new IllegalStateException("unsupported parsed Explore field: " + term.field());
        };
        if (term.negated()) sql.append("NOT (").append(expression).append(')');
        else sql.append(expression);
        if ("tag".equals(term.field())) {
            args.add(term.tagKey());
            args.add(term.value());
            args.add(term.tagKey());
            args.add(term.value());
        } else {
            args.add(term.value());
        }
    }

    public MonitorIssue issueByFingerprint(Long projectId, String fingerprint) {
        return issueMapper.selectOne(
                Wrappers.<MonitorIssue>lambdaQuery()
                        .eq(MonitorIssue::getProjectId, projectId)
                        .eq(MonitorIssue::getFingerprint, fingerprint)
                        .last("LIMIT 1")
        );
    }

    public List<MonitorRelease> releases(Long projectId) {
        return releaseMapper.selectList(
                Wrappers.<MonitorRelease>lambdaQuery()
                        .eq(MonitorRelease::getProjectId, projectId)
                        .orderByDesc(MonitorRelease::getDeployTime)
                        .orderByDesc(MonitorRelease::getId)
        );
    }

    public List<MonitorReplay> replays(Long projectId, String sessionId) {
        var query = Wrappers.<MonitorReplay>lambdaQuery()
                .eq(MonitorReplay::getProjectId, projectId)
                .orderByDesc(MonitorReplay::getId);
        if (StringUtils.hasText(sessionId)) {
            query.eq(MonitorReplay::getSessionId, sessionId);
        }
        return replayMapper.selectList(query.last("LIMIT 1000"));
    }

    public MonitorReplay replay(Long projectId, Long replayId) {
        return replayMapper.selectOne(
                Wrappers.<MonitorReplay>lambdaQuery()
                        .eq(MonitorReplay::getProjectId, projectId)
                        .eq(MonitorReplay::getId, replayId)
                        .last("LIMIT 1")
        );
    }

    public List<MonitorAlertRule> alertRules(Long projectId) {
        return alertRuleMapper.selectList(
                Wrappers.<MonitorAlertRule>lambdaQuery()
                        .eq(MonitorAlertRule::getProjectId, projectId)
                        .orderByDesc(MonitorAlertRule::getId)
        );
    }

    public List<MonitorAlertRecord> alertRecords(Long projectId) {
        return alertRecordMapper.selectList(
                Wrappers.<MonitorAlertRecord>lambdaQuery()
                        .eq(MonitorAlertRecord::getProjectId, projectId)
                        .orderByDesc(MonitorAlertRecord::getTriggeredAt)
                        .last("LIMIT 200")
        );
    }

    private EventFilter eventFilter(
            MonitorProject project,
            int hours,
            String environment,
            String release) {
        int safeHours = Math.max(1, Math.min(24 * 365, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        StringBuilder where = new StringBuilder(" WHERE project_id=? AND event_time>=?");
        List<Object> args = new ArrayList<>();
        args.add(project.getProjectKey());
        args.add(since);

        if (StringUtils.hasText(environment)) {
            where.append(" AND environment=?");
            args.add(environment);
        }
        if (StringUtils.hasText(release)) {
            where.append(" AND release=?");
            args.add(release);
        }
        return new EventFilter(where.toString(), args.toArray());
    }
}
