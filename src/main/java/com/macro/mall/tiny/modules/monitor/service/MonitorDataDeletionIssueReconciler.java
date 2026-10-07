package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionItemMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionJobMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class MonitorDataDeletionIssueReconciler {
    private final MonitorDataDeletionItemMapper itemMapper;
    private final MonitorIssueMapper issueMapper;
    private final MonitorDataDeletionJobMapper jobMapper;
    private final JdbcTemplate clickHouse;

    public MonitorDataDeletionIssueReconciler(
            MonitorDataDeletionItemMapper itemMapper,
            MonitorIssueMapper issueMapper,
            MonitorDataDeletionJobMapper jobMapper,
            @Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.itemMapper = itemMapper;
        this.issueMapper = issueMapper;
        this.jobMapper = jobMapper;
        this.clickHouse = clickHouse;
    }

    /** Applies one issue adjustment and its MySQL checkpoint in one transaction. */
    @Transactional
    public boolean reconcileNext(MonitorDataDeletionJob job) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> next = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId())
                .eq(MonitorDataDeletionItem::getItemType, "FINGERPRINT")
                .gt(MonitorDataDeletionItem::getId, cursor)
                .orderByAsc(MonitorDataDeletionItem::getId)
                .last("LIMIT 1"));
        if (next.isEmpty()) return false;

        MonitorDataDeletionItem item = next.get(0);
        String fingerprint = item.getItemValue();
        List<Map<String, Object>> stats = clickHouse.queryForList(
                "SELECT count() AS event_count, uniqExactIf(user_id,user_id!='') AS affected_users, " +
                        "min(event_time) AS first_seen, max(event_time) AS last_seen, argMax(release,event_time) AS latest_release " +
                        "FROM monitor.error_event WHERE project_id=? AND fingerprint=?",
                job.getProjectKey(), fingerprint);
        Map<String, Object> stat = stats.get(0);
        long retainedEvents = ((Number) stat.get("event_count")).longValue();
        MonitorIssue issue = issueMapper.selectOne(Wrappers.<MonitorIssue>lambdaQuery()
                .eq(MonitorIssue::getProjectId, job.getProjectId())
                .eq(MonitorIssue::getFingerprint, fingerprint)
                .last("LIMIT 1"));

        if (issue != null) {
            Long deletedCount = itemMapper.selectCount(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                    .eq(MonitorDataDeletionItem::getJobId, job.getId())
                    .eq(MonitorDataDeletionItem::getItemType, "FINGERPRINT_DECREMENT")
                    .likeRight(MonitorDataDeletionItem::getItemValue, fingerprint + "#"));
            long adjustedCount = Math.max(retainedEvents,
                    Math.max(0L, issue.getEventCount() - (deletedCount == null ? 0L : deletedCount)));
            if (adjustedCount == 0) {
                issueMapper.deleteById(issue.getId());
            } else {
                issue.setEventCount(adjustedCount);
                issue.setAffectedUsers(((Number) stat.get("affected_users")).longValue());
                if (retainedEvents > 0) {
                    issue.setFirstSeen(toDate(stat.get("first_seen")));
                    issue.setLastSeen(toDate(stat.get("last_seen")));
                    issue.setLatestRelease(String.valueOf(stat.get("latest_release")));
                }
                issueMapper.updateById(issue);
            }
        }

        job.setCursorValue(Long.toString(item.getId()));
        jobMapper.updateById(job);
        return true;
    }

    private long parseCursor(String value) {
        try { return Long.parseLong(value == null ? "0" : value); } catch (NumberFormatException ignored) { return 0; }
    }

    private Date toDate(Object value) {
        if (value instanceof Date date) return date;
        if (value instanceof java.time.LocalDateTime time) return Date.from(time.toInstant(java.time.ZoneOffset.UTC));
        return null;
    }
}
