package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataDeletionRequest;
import com.macro.mall.tiny.modules.monitor.mapper.*;
import com.macro.mall.tiny.modules.monitor.model.*;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Owner-only, durable data erasure workflow. Each stage is repeatable; ClickHouse mutations sync before advancing. */
@Service
@RequiredArgsConstructor
public class MonitorDataDeletionService {
    private static final int BATCH_SIZE = 300;
    private static final String QUEUED = "QUEUED";
    private final MonitorProjectAccessService accessService;
    private final MonitorDataDeletionJobMapper jobMapper;
    private final MonitorDataDeletionItemMapper itemMapper;
    private final MonitorReplayMapper replayMapper;
    private final MonitorIssueMapper issueMapper;
    private final MonitorDataDeletionIssueReconciler issueReconciler;
    private final MonitorErrorHourlyDeletionReconciler hourlyReconciler;
    private final MonitorDataDeletionAuditService auditService;
    private final StringRedisTemplate redis;
    private final MinioClient minio;
    private final ObjectMapper objectMapper;
    @Qualifier("clickHouseJdbcTemplate")
    private final JdbcTemplate clickHouse;

    @Value("${monitor.minio.replay-bucket:monitor-replays}")
    private String replayBucket;
    @Value("${monitor.data-deletion.worker-enabled:false}")
    private boolean workerEnabled;

    public MonitorDataDeletionJob preview(String projectKey, MonitorDataDeletionRequest request) {
        MonitorProject project = accessService.requireProjectOwner(projectKey);
        Instant from = request.getFrom();
        Instant to = request.getTo();
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(Instant.now())
                || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range must be past, non-empty, and at most 90 days");
        }
        String userId = StringUtils.hasText(request.getUserId()) ? request.getUserId().trim() : null;
        if (userId != null && userId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be at most 128 characters");
        }
        if (userId != null && from.isBefore(Instant.now().minus(Duration.ofDays(14)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "user-scoped Replay deletion is limited to the latest 14 days because older Replay events no longer retain their userId association; narrow the date range or omit userId");
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String table : List.of("error_event", "performance_event", "behavior_event", "replay_event", "metric_event", "profile_event")) {
            counts.put(table, countEvents(table, project.getProjectKey(), from, to, userId));
        }
        if (userId == null) {
            Long replayIndexes = replayMapper.selectCount(Wrappers.<MonitorReplay>lambdaQuery()
                    .eq(MonitorReplay::getProjectId, project.getId())
                    .ge(MonitorReplay::getStartTime, Date.from(from))
                    .lt(MonitorReplay::getStartTime, Date.from(to)));
            counts.put("replayObjects", replayIndexes == null ? 0L : replayIndexes);
        } else {
            counts.put("replayObjects", counts.get("replay_event"));
            counts.put("replayObjectsCountSemantics", "exact for the selected 14-day retention window");
        }
        counts.put("countSemantics", "preview counts are exact when generated; execution snapshots records persisted through job start and excludes later arrivals");
        String token = sha256(project.getId() + ":" + from + ":" + to + ":" + userId + ":" + UUID.randomUUID());
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setProjectId(project.getId());
        job.setProjectKey(project.getProjectKey());
        job.setUserId(userId);
        job.setRangeStart(Date.from(from));
        job.setRangeEnd(Date.from(to));
        job.setStatus("PREVIEW");
        job.setStage("PREVIEW");
        job.setPreviewToken(token);
        job.setPreviewCountsJson(writeJson(counts));
        job.setRequestedBy(accessService.currentAdminId());
        jobMapper.insert(job);
        return job;
    }

    @Transactional
    public MonitorDataDeletionJob execute(String projectKey, Long previewId, String token) {
        MonitorProject project = accessService.requireProjectOwner(projectKey);
        MonitorDataDeletionJob job = requireJob(project, previewId);
        boolean expired = job.getCreateTime() == null || job.getCreateTime().toInstant().isBefore(Instant.now().minus(Duration.ofMinutes(30)));
        if (!"PREVIEW".equals(job.getStatus()) || expired || !MessageDigest.isEqual(
                job.getPreviewToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Objects.toString(token, "").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "preview is stale or token is invalid");
        }
        job.setStatus(QUEUED);
        job.setLeaseUntil(null);
        job.setStage("SNAPSHOT_ERROR_EVENTS");
        job.setStartedAt(new Date());
        job.setDeletedCountsJson("{}");
        jobMapper.updateById(job);
        return job;
    }

    public MonitorDataDeletionJob status(String projectKey, Long jobId) {
        MonitorProject project = accessService.requireProjectOwner(projectKey);
        return requireJob(project, jobId);
    }

    public List<MonitorDataDeletionJob> recentJobs(String projectKey) {
        MonitorProject project = accessService.requireProjectOwner(projectKey);
        List<MonitorDataDeletionJob> jobs = jobMapper.selectList(Wrappers.<MonitorDataDeletionJob>lambdaQuery()
                .eq(MonitorDataDeletionJob::getProjectId, project.getId())
                .orderByDesc(MonitorDataDeletionJob::getId)
                .last("LIMIT 20"));
        jobs.forEach(job -> job.setPreviewToken(null));
        return jobs;
    }

    @Scheduled(fixedDelayString = "${monitor.data-deletion.worker-delay-ms:3000}")
    public void processOneBatch() {
        if (!workerEnabled) return;
        MonitorDataDeletionJob job = jobMapper.selectOne(Wrappers.<MonitorDataDeletionJob>lambdaQuery()
                .and(query -> query.eq(MonitorDataDeletionJob::getStatus, QUEUED)
                        .or().eq(MonitorDataDeletionJob::getStatus, "RUNNING")
                        .lt(MonitorDataDeletionJob::getLeaseUntil, new Date()))
                .orderByAsc(MonitorDataDeletionJob::getId).last("LIMIT 1"));
        if (job == null) return;
        if (jobMapper.claim(job.getId()) != 1) return;
        job.setStatus("RUNNING");
        job.setLeaseUntil(Date.from(Instant.now().plus(Duration.ofMinutes(10))));
        try {
            if (job.getStartedAt() == null) {
                job.setStartedAt(new Date());
                jobMapper.updateById(job);
            }
            switch (job.getStage()) {
                case "SNAPSHOT_ERROR_EVENTS" -> snapshotEventBatch(job, "error_event", "SNAPSHOT_PERFORMANCE_EVENTS", true);
                case "SNAPSHOT_PERFORMANCE_EVENTS" -> snapshotEventBatch(job, "performance_event", "SNAPSHOT_BEHAVIOR_EVENTS", false);
                case "SNAPSHOT_BEHAVIOR_EVENTS" -> snapshotEventBatch(job, "behavior_event", "SNAPSHOT_METRIC_EVENTS", false);
                case "SNAPSHOT_METRIC_EVENTS" -> snapshotEventBatch(job, "metric_event", "SNAPSHOT_PROFILE_EVENTS", false);
                case "SNAPSHOT_PROFILE_EVENTS" -> snapshotEventBatch(job, "profile_event", "SNAPSHOT_REPLAY_EVENTS", false);
                case "SNAPSHOT_REPLAY_EVENTS" -> snapshotEventBatch(job, "replay_event", "SNAPSHOT_REPLAY_INDEXES", false);
                case "SNAPSHOT_REPLAY_INDEXES" -> snapshotReplayIndexBatch(job);
                case "REPLAY_OBJECTS" -> deleteReplayBatch(job);
                case "DELETE_METRIC" -> deleteSnapshotBatch(job, "metric_event", "METRIC_EVENT", "DELETE_PROFILE");
                case "DELETE_PROFILE" -> deleteSnapshotBatch(job, "profile_event", "PROFILE_EVENT", "DELETE_PERFORMANCE");
                case "DELETE_PERFORMANCE" -> deleteSnapshotBatch(job, "performance_event", "PERFORMANCE_EVENT", "DELETE_BEHAVIOR");
                case "DELETE_BEHAVIOR" -> deleteSnapshotBatch(job, "behavior_event", "BEHAVIOR_EVENT", "DELETE_REPLAY_EVENT");
                case "DELETE_REPLAY_EVENT" -> deleteSnapshotBatch(job, "replay_event", "REPLAY_EVENT", "DELETE_ERROR_EVENT");
                case "DELETE_ERROR_EVENT" -> deleteSnapshotBatch(job, "error_event", "ERROR_EVENT", "RECONCILE_ERROR_HOURLY");
                case "RECONCILE_ERROR_HOURLY" -> reconcileErrorHourly(job);
                case "RECONCILE_ISSUES" -> reconcileIssues(job);
                case "REBUILD_ISSUE_HLLS" -> rebuildIssueHllBatch(job);
                case "CLEAN_REDIS_DEDUP" -> cleanRedisDedupBatch(job);
                default -> throw new IllegalStateException("unknown deletion stage: " + job.getStage());
            }
            if ("RUNNING".equals(job.getStatus())) {
                job.setStatus(QUEUED);
                job.setLeaseUntil(null);
                jobMapper.updateById(job);
            }
        } catch (Exception e) {
            job.setStatus("FAILED");
            job.setLeaseUntil(null);
            job.setErrorMessage(truncate(e.getMessage(), 1000));
            jobMapper.updateById(job);
        }
    }

    public MonitorDataDeletionJob retry(String projectKey, Long jobId) {
        MonitorProject project = accessService.requireProjectOwner(projectKey);
        MonitorDataDeletionJob job = requireJob(project, jobId);
        if (!"FAILED".equals(job.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "job is not retryable");
        job.setStatus(QUEUED);
        job.setLeaseUntil(null);
        job.setErrorMessage(null);
        jobMapper.updateById(job);
        return job;
    }

    private void snapshotEventBatch(MonitorDataDeletionJob job, String table, String nextStage, boolean includeFingerprints) {
        String cursor = Objects.toString(job.getCursorValue(), "");
        List<Map<String, Object>> rows = selectEventBatch(table, job, cursor);
        for (Map<String, Object> row : rows) {
            String eventId = Objects.toString(row.get("event_id"), "");
            saveItem(job.getId(), "EVENT_ID_" + table.toUpperCase(Locale.ROOT), eventId);
            if (includeFingerprints) {
                saveErrorHourlySnapshot(job.getId(), eventId, Objects.toString(row.get("hourly_deltas"), "[]"));
            }
        }
        if (rows.size() < BATCH_SIZE) {
            job.setCursorValue(null);
            job.setStage(nextStage);
        } else {
            job.setCursorValue(Objects.toString(rows.get(rows.size() - 1).get("event_id"), ""));
        }
        jobMapper.updateById(job);
    }

    private void deleteReplayBatch(MonitorDataDeletionJob job) throws Exception {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> items = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId()).eq(MonitorDataDeletionItem::getItemType, "REPLAY_INDEX_ID")
                .gt(MonitorDataDeletionItem::getId, cursor).orderByAsc(MonitorDataDeletionItem::getId).last("LIMIT " + BATCH_SIZE));
        for (MonitorDataDeletionItem item : items) {
            MonitorReplay replay = replayMapper.selectById(Long.parseLong(item.getItemValue()));
            if (replay != null) {
                minio.removeObject(RemoveObjectArgs.builder().bucket(replayBucket).object(replay.getObjectKey()).build());
                replayMapper.deleteById(replay.getId());
            }
            cursor = item.getId();
        }
        if (items.size() < BATCH_SIZE) {
            job.setCursorValue(null);
            job.setStage("DELETE_METRIC");
        } else {
            job.setCursorValue(Long.toString(cursor));
        }
        jobMapper.updateById(job);
    }

    private void snapshotReplayIndexBatch(MonitorDataDeletionJob job) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorReplay> indexes;
        if (!StringUtils.hasText(job.getUserId())) {
            indexes = replayMapper.selectForDeletionBatch(
                    job.getProjectId(), job.getRangeStart(), job.getRangeEnd(), job.getStartedAt(), cursor, BATCH_SIZE);
            for (MonitorReplay replay : indexes) saveItem(job.getId(), "REPLAY_INDEX_ID", Long.toString(replay.getId()));
            if (indexes.size() == BATCH_SIZE) cursor = indexes.get(indexes.size() - 1).getId();
            else cursor = -1;
        } else {
            List<MonitorDataDeletionItem> eventItems = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                    .eq(MonitorDataDeletionItem::getJobId, job.getId()).eq(MonitorDataDeletionItem::getItemType, "EVENT_ID_REPLAY_EVENT")
                    .gt(MonitorDataDeletionItem::getId, cursor).orderByAsc(MonitorDataDeletionItem::getId).last("LIMIT " + BATCH_SIZE));
            for (MonitorDataDeletionItem eventItem : eventItems) {
                MonitorReplay replay = replayMapper.selectOne(Wrappers.<MonitorReplay>lambdaQuery()
                        .eq(MonitorReplay::getProjectId, job.getProjectId()).eq(MonitorReplay::getEventId, eventItem.getItemValue())
                        .le(MonitorReplay::getCreateTime, job.getStartedAt()).last("LIMIT 1"));
                if (replay != null) saveItem(job.getId(), "REPLAY_INDEX_ID", Long.toString(replay.getId()));
                cursor = eventItem.getId();
            }
            if (eventItems.size() < BATCH_SIZE) cursor = -1;
        }
        if (cursor < 0) {
            job.setCursorValue(null);
            job.setStage("REPLAY_OBJECTS");
        } else {
            job.setCursorValue(Long.toString(cursor));
        }
        jobMapper.updateById(job);
    }

    private void deleteSnapshotBatch(MonitorDataDeletionJob job, String table, String itemSuffix, String nextStage) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> items = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId()).eq(MonitorDataDeletionItem::getItemType, "EVENT_ID_" + itemSuffix)
                .gt(MonitorDataDeletionItem::getId, cursor).orderByAsc(MonitorDataDeletionItem::getId).last("LIMIT " + BATCH_SIZE));
        if (items.isEmpty()) {
            recordDeletedCount(job, table, itemSuffix);
            job.setCursorValue(null);
            job.setStage(nextStage);
            jobMapper.updateById(job);
            return;
        }
        List<Object> args = new ArrayList<>();
        args.add(job.getProjectKey());
        args.addAll(items.stream().map(MonitorDataDeletionItem::getItemValue).toList());
        String placeholders = String.join(",", Collections.nCopies(items.size(), "?"));
        String mutation = "ALTER TABLE monitor." + table + " DELETE WHERE project_id=? AND event_id IN (" + placeholders + ") SETTINGS mutations_sync=2";
        clickHouse.execute((org.springframework.jdbc.core.PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement(mutation);
            for (int index = 0; index < args.size(); index++) statement.setObject(index + 1, args.get(index));
            return statement;
        }, (org.springframework.jdbc.core.PreparedStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
        job.setCursorValue(Long.toString(items.get(items.size() - 1).getId()));
        jobMapper.updateById(job);
    }

    private void reconcileIssues(MonitorDataDeletionJob job) {
        if (issueReconciler.reconcileNext(job)) return;
        job.setCursorValue(null);
        job.setStage("REBUILD_ISSUE_HLLS");
        jobMapper.updateById(job);
    }

    private void reconcileErrorHourly(MonitorDataDeletionJob job) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> items = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId())
                .eq(MonitorDataDeletionItem::getItemType, "ERROR_HOURLY_DELTA")
                .gt(MonitorDataDeletionItem::getId, cursor)
                .orderByAsc(MonitorDataDeletionItem::getId)
                .last("LIMIT " + BATCH_SIZE));
        hourlyReconciler.reconcile(job, items);
        if (items.size() < BATCH_SIZE) {
            job.setCursorValue(null);
            job.setStage("RECONCILE_ISSUES");
        } else {
            job.setCursorValue(Long.toString(items.get(items.size() - 1).getId()));
        }
        jobMapper.updateById(job);
    }

    private void rebuildIssueHllBatch(MonitorDataDeletionJob job) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> fingerprints = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId()).eq(MonitorDataDeletionItem::getItemType, "FINGERPRINT")
                .gt(MonitorDataDeletionItem::getId, cursor).orderByAsc(MonitorDataDeletionItem::getId).last("LIMIT 100"));
        for (MonitorDataDeletionItem item : fingerprints) {
            String fingerprint = item.getItemValue();
            MonitorIssue issue = issueMapper.selectOne(Wrappers.<MonitorIssue>lambdaQuery()
                    .eq(MonitorIssue::getProjectId, job.getProjectId()).eq(MonitorIssue::getFingerprint, fingerprint).last("LIMIT 1"));
            if (issue == null) redis.delete("monitor:issue:users:" + job.getProjectId() + ":" + fingerprint);
            else rebuildHll(job.getProjectKey(), job.getProjectId(), fingerprint);
            cursor = item.getId();
        }
        if (fingerprints.size() < 100) {
            job.setCursorValue(null);
            job.setStage("CLEAN_REDIS_DEDUP");
        } else {
            job.setCursorValue(Long.toString(cursor));
        }
        jobMapper.updateById(job);
    }

    private void cleanRedisDedupBatch(MonitorDataDeletionJob job) {
        long cursor = parseCursor(job.getCursorValue());
        List<MonitorDataDeletionItem> eventIds = itemMapper.selectList(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId()).in(MonitorDataDeletionItem::getItemType,
                        "EVENT_ID_ERROR_EVENT", "EVENT_ID_PERFORMANCE_EVENT", "EVENT_ID_BEHAVIOR_EVENT",
                        "EVENT_ID_REPLAY_EVENT", "EVENT_ID_METRIC_EVENT", "EVENT_ID_PROFILE_EVENT")
                .gt(MonitorDataDeletionItem::getId, cursor).orderByAsc(MonitorDataDeletionItem::getId).last("LIMIT 500"));
        for (MonitorDataDeletionItem item : eventIds) {
            redis.delete("monitor:issue:aggregated:" + item.getItemValue());
            redis.delete("monitor:event:processed:" + item.getItemValue());
            cursor = item.getId();
        }
        if (eventIds.size() < 500) {
            auditService.complete(job);
        } else {
            job.setCursorValue(Long.toString(cursor));
            jobMapper.updateById(job);
        }
    }

    private void rebuildHll(String projectKey, Long projectId, String fingerprint) {
        String key = "monitor:issue:users:" + projectId + ":" + fingerprint;
        redis.delete(key);
        String cursor = "";
        while (true) {
            List<String> users = clickHouse.queryForList(
                    "SELECT DISTINCT user_id FROM monitor.error_event WHERE project_id=? AND fingerprint=? AND user_id!='' AND user_id>? ORDER BY user_id LIMIT 500",
                    String.class, projectKey, fingerprint, cursor);
            if (!users.isEmpty()) {
                redis.opsForHyperLogLog().add(key, users.toArray(String[]::new));
                cursor = users.get(users.size() - 1);
            }
            if (users.size() < 500) break;
        }
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.expire(key, Duration.ofDays(90));
        }
    }

    private List<Map<String, Object>> selectEventBatch(String table, MonitorDataDeletionJob job, String cursor) {
        if ("error_event".equals(table)) {
            String sql = "SELECT event_id, toJSONString(groupArray((bucket_epoch, fingerprint, event_count))) AS hourly_deltas FROM (" +
                    "SELECT event_id, toUnixTimestamp(toStartOfHour(event_time)) AS bucket_epoch, fingerprint, count() AS event_count " +
                    "FROM monitor.error_event WHERE project_id=? AND event_time>=? AND event_time<? AND received_at<=?" +
                    (StringUtils.hasText(job.getUserId()) ? " AND user_id=?" : "") +
                    " AND event_id>? GROUP BY event_id, bucket_epoch, fingerprint) " +
                    "GROUP BY event_id ORDER BY event_id LIMIT " + BATCH_SIZE;
            List<Object> args = new ArrayList<>(List.of(job.getProjectKey(), new Timestamp(job.getRangeStart().getTime()), new Timestamp(job.getRangeEnd().getTime())));
            args.add(new Timestamp(job.getStartedAt().getTime()));
            if (StringUtils.hasText(job.getUserId())) args.add(job.getUserId());
            args.add(cursor);
            return clickHouse.queryForList(sql, args.toArray());
        }
        String sql = "SELECT event_id,fingerprint FROM monitor." + table + " WHERE project_id=? AND event_time>=? AND event_time<? AND received_at<=?" +
                (StringUtils.hasText(job.getUserId()) ? " AND user_id=?" : "") + " AND event_id>? GROUP BY event_id,fingerprint ORDER BY event_id LIMIT " + BATCH_SIZE;
        List<Object> args = new ArrayList<>(List.of(job.getProjectKey(), new Timestamp(job.getRangeStart().getTime()), new Timestamp(job.getRangeEnd().getTime())));
        args.add(new Timestamp(job.getStartedAt().getTime()));
        if (StringUtils.hasText(job.getUserId())) args.add(job.getUserId());
        args.add(cursor);
        return clickHouse.queryForList(sql, args.toArray());
    }

    private long countEvents(String table, String projectKey, Instant from, Instant to, String userId) {
        String sql = "SELECT uniqExact(event_id) FROM monitor." + table + " WHERE project_id=? AND event_time>=? AND event_time<?" +
                (userId == null ? "" : " AND user_id=?");
        Object[] args = userId == null
                ? new Object[]{projectKey, Timestamp.from(from), Timestamp.from(to)}
                : new Object[]{projectKey, Timestamp.from(from), Timestamp.from(to), userId};
        Long count = clickHouse.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private void saveItem(Long jobId, String type, String value) {
        saveItem(jobId, type, sha256(type + ":" + value), value);
    }

    private void saveItem(Long jobId, String type, String idempotencyKey, String value) {
        if (!StringUtils.hasText(value)) return;
        MonitorDataDeletionItem item = new MonitorDataDeletionItem();
        item.setJobId(jobId); item.setItemType(type); item.setIdempotencyKey(idempotencyKey); item.setItemValue(value);
        try { itemMapper.insert(item); } catch (org.springframework.dao.DuplicateKeyException ignored) { }
    }

    private void saveErrorHourlySnapshot(Long jobId, String eventId, String hourlyDeltas) {
        try {
            var rows = objectMapper.readTree(hourlyDeltas);
            for (var row : rows) {
                if (!row.isArray() || row.size() != 3) throw new IllegalArgumentException("invalid error_hourly snapshot row");
                long bucketEpoch = row.get(0).asLong();
                String fingerprint = row.get(1).asText();
                long eventCount = row.get(2).asLong();
                if (eventCount < 1) throw new IllegalArgumentException("invalid error_hourly snapshot count");

                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("bucketEpoch", bucketEpoch);
                delta.put("fingerprint", fingerprint);
                delta.put("eventCount", eventCount);
                String idempotencyKey = sha256(eventId + ":" + bucketEpoch + ":" + fingerprint);
                saveItem(jobId, "ERROR_HOURLY_DELTA", idempotencyKey, writeJson(delta));
                saveItem(jobId, "FINGERPRINT", fingerprint);
                saveItem(jobId, "FINGERPRINT_DECREMENT", fingerprint + "#" + eventId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not persist error_hourly correction snapshot", e);
        }
    }

    private void recordDeletedCount(MonitorDataDeletionJob job, String table, String itemSuffix) {
        Long count = itemMapper.selectCount(Wrappers.<MonitorDataDeletionItem>lambdaQuery()
                .eq(MonitorDataDeletionItem::getJobId, job.getId())
                .eq(MonitorDataDeletionItem::getItemType, "EVENT_ID_" + itemSuffix));
        Map<String, Object> counts;
        try {
            String existing = StringUtils.hasText(job.getDeletedCountsJson()) ? job.getDeletedCountsJson() : "{}";
            counts = objectMapper.readValue(existing, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("stored deletion count data is invalid", e);
        }
        counts.put(table, count == null ? 0L : count);
        job.setDeletedCountsJson(writeJson(counts));
    }

    private MonitorDataDeletionJob requireJob(MonitorProject project, Long id) {
        MonitorDataDeletionJob job = jobMapper.selectById(id);
        if (job == null || !project.getId().equals(job.getProjectId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "deletion job not found");
        return job;
    }

    private long parseCursor(String cursor) {
        try { return Long.parseLong(Objects.toString(cursor, "0")); } catch (NumberFormatException ignored) { return 0; }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String truncate(String value, int limit) { return value == null ? "deletion stage failed" : value.substring(0, Math.min(value.length(), limit)); }
}
