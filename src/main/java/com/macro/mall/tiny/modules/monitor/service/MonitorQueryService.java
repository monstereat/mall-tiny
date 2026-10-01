package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.monitor.mapper.*;
import com.macro.mall.tiny.modules.monitor.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitorQueryService {

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

    public Map<String, Object> dashboard(MonitorProject project, int hours) {
        int safeHours = Math.max(1, Math.min(24 * 30, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        Map<String, Object> result = new LinkedHashMap<>();

        Long errors = clickHouse.queryForObject(
                "SELECT count() FROM monitor.error_event WHERE project_id=? AND event_time >= ?",
                Long.class,
                project.getProjectKey(), since
        );
        Long affectedUsers = clickHouse.queryForObject(
                "SELECT uniqExactIf(user_id, user_id != '') FROM monitor.error_event WHERE project_id=? AND event_time >= ?",
                Long.class,
                project.getProjectKey(), since
        );
        Long apiEvents = clickHouse.queryForObject(
                "SELECT countIf(JSONExtractString(payload, 'data', 'category')='api') FROM monitor.behavior_event WHERE project_id=? AND event_time >= ?",
                Long.class,
                project.getProjectKey(), since
        );
        Long unresolved = issueMapper.selectCount(
                Wrappers.<MonitorIssue>lambdaQuery()
                        .eq(MonitorIssue::getProjectId, project.getId())
                        .eq(MonitorIssue::getStatus, "unresolved")
        );

        result.put("errorCount", errors == null ? 0L : errors);
        result.put("affectedUsers", affectedUsers == null ? 0L : affectedUsers);
        result.put("apiEvents", apiEvents == null ? 0L : apiEvents);
        result.put("unresolvedIssues", unresolved == null ? 0L : unresolved);
        result.put("errorTrend", clickHouse.queryForList(
                "SELECT bucket, sum(event_count) AS count FROM monitor.error_hourly WHERE project_id=? AND bucket >= ? GROUP BY bucket ORDER BY bucket",
                project.getProjectKey(), since
        ));
        result.put("webVitals", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','metric') AS metric, avg(JSONExtractFloat(payload,'data','value')) AS value " +
                        "FROM monitor.performance_event WHERE project_id=? AND event_time >= ? " +
                        "AND JSONExtractString(payload,'data','metric') IN ('FCP','LCP','CLS','TTFB','INP') GROUP BY metric ORDER BY metric",
                project.getProjectKey(), since
        ));
        return result;
    }

    public IPage<MonitorIssue> issues(Long projectId, long pageNum, long pageSize, String status) {
        var query = Wrappers.<MonitorIssue>lambdaQuery()
                .eq(MonitorIssue::getProjectId, projectId)
                .orderByDesc(MonitorIssue::getLastSeen);
        if (status != null && !status.isBlank()) {
            query.eq(MonitorIssue::getStatus, status);
        }
        return issueMapper.selectPage(Page.of(pageNum, pageSize), query);
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
                "SELECT event_id,event_time,session_id,user_id,release,page_url,payload " +
                        "FROM monitor.error_event WHERE project_id=? AND fingerprint=? ORDER BY event_time DESC LIMIT ?",
                project.getProjectKey(), issue.getFingerprint(), Math.max(1, Math.min(100, limit))
        );
    }

    public Map<String, Object> performance(MonitorProject project, int hours) {
        int safeHours = Math.max(1, Math.min(24 * 30, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','metric') AS metric, " +
                        "avg(JSONExtractFloat(payload,'data','value')) AS avgValue, " +
                        "quantile(0.75)(JSONExtractFloat(payload,'data','value')) AS p75, " +
                        "quantile(0.95)(JSONExtractFloat(payload,'data','value')) AS p95, count() AS samples " +
                        "FROM monitor.performance_event WHERE project_id=? AND event_time>=? " +
                        "GROUP BY metric ORDER BY metric",
                project.getProjectKey(), since
        ));
        result.put("trend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket, JSONExtractString(payload,'data','metric') AS metric, " +
                        "avg(JSONExtractFloat(payload,'data','value')) AS value " +
                        "FROM monitor.performance_event WHERE project_id=? AND event_time>=? " +
                        "GROUP BY bucket,metric ORDER BY bucket,metric",
                project.getProjectKey(), since
        ));
        result.put("recent", clickHouse.queryForList(
                "SELECT event_time,page_url,release,JSONExtractString(payload,'data','metric') AS metric," +
                        "JSONExtractFloat(payload,'data','value') AS value FROM monitor.performance_event " +
                        "WHERE project_id=? AND event_time>=? ORDER BY event_time DESC LIMIT 200",
                project.getProjectKey(), since
        ));
        return result;
    }

    public Map<String, Object> apiPerformance(MonitorProject project, int hours) {
        int safeHours = Math.max(1, Math.min(24 * 30, hours));
        Timestamp since = Timestamp.from(Instant.now().minus(safeHours, ChronoUnit.HOURS));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", clickHouse.queryForList(
                "SELECT JSONExtractString(payload,'data','url') AS url, count() AS requests, " +
                        "avg(JSONExtractFloat(payload,'data','duration')) AS avgRt, " +
                        "quantile(0.95)(JSONExtractFloat(payload,'data','duration')) AS p95, " +
                        "countIf(JSONExtractInt(payload,'data','status')>=400) AS failures " +
                        "FROM monitor.behavior_event WHERE project_id=? AND event_time>=? " +
                        "AND JSONExtractString(payload,'data','category')='api' GROUP BY url ORDER BY requests DESC LIMIT 100",
                project.getProjectKey(), since
        ));
        result.put("trend", clickHouse.queryForList(
                "SELECT toStartOfHour(event_time) AS bucket, count() AS requests, " +
                        "countIf(JSONExtractInt(payload,'data','status')>=400) AS failures, " +
                        "avg(JSONExtractFloat(payload,'data','duration')) AS avgRt " +
                        "FROM monitor.behavior_event WHERE project_id=? AND event_time>=? " +
                        "AND JSONExtractString(payload,'data','category')='api' GROUP BY bucket ORDER BY bucket",
                project.getProjectKey(), since
        ));
        return result;
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
        if (sessionId != null && !sessionId.isBlank()) {
            query.eq(MonitorReplay::getSessionId, sessionId);
        }
        return replayMapper.selectList(query.last("LIMIT 100"));
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
}