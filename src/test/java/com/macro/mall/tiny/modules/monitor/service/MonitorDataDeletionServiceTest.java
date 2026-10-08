package com.macro.mall.tiny.modules.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataDeletionRequest;
import com.macro.mall.tiny.modules.monitor.mapper.*;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;

class MonitorDataDeletionServiceTest {
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
    private final MonitorDataDeletionJobMapper jobMapper = mock(MonitorDataDeletionJobMapper.class);
    private final MonitorDataDeletionItemMapper itemMapper = mock(MonitorDataDeletionItemMapper.class);
    private final MonitorReplayMapper replayMapper = mock(MonitorReplayMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorDataDeletionIssueReconciler issueReconciler = mock(MonitorDataDeletionIssueReconciler.class);
    private final MonitorErrorHourlyDeletionReconciler hourlyReconciler = mock(MonitorErrorHourlyDeletionReconciler.class);
    private final MonitorDataDeletionAuditService auditService = mock(MonitorDataDeletionAuditService.class);
    private final MonitorLokiDeletionClient lokiDeletionClient = mock(MonitorLokiDeletionClient.class);
    private final org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    private final MonitorReplayQuotaService replayQuotaService = mock(MonitorReplayQuotaService.class);
    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorDataDeletionService service = new MonitorDataDeletionService(
            accessService, projectMapper, jobMapper, itemMapper, replayMapper, issueMapper, issueReconciler, hourlyReconciler,
            auditService, lokiDeletionClient, redis, replayQuotaService, new ObjectMapper(), clickHouse);

    @BeforeEach
    void ownerProjectAvailable() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "data-deletion-test"),
                MonitorReplay.class);
        MonitorProject project = new MonitorProject();
        project.setId(1L);
        project.setProjectKey("demo");
        when(accessService.requireProjectOwner("demo")).thenReturn(project);
        when(projectMapper.lockProject(1L)).thenReturn(1L);
    }

    @Test
    void rejectsEmptyRangeBeforeQueryOrAuditCreation() {
        MonitorDataDeletionRequest request = request("2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.preview("demo", request));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(clickHouse, jobMapper);
    }

    @Test
    void rejectsRangeLongerThanNinetyDays() {
        MonitorDataDeletionRequest request = request("2026-01-01T00:00:00Z", "2026-04-10T00:00:00Z");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.preview("demo", request));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(clickHouse, jobMapper);
    }

    @Test
    void workerDoesNotPollUntilExplicitlyEnabled() {
        service.processOneBatch();
        verifyNoInteractions(jobMapper);
    }

    @Test
    void nullableWorkerProgressFieldsAreWrittenWhenCleared() throws Exception {
        for (String fieldName : java.util.List.of("cursorValue", "errorMessage", "leaseUntil")) {
            TableField mapping = MonitorDataDeletionJob.class.getDeclaredField(fieldName).getAnnotation(TableField.class);
            assertEquals(FieldStrategy.ALWAYS, mapping.updateStrategy(), fieldName);
        }
    }

    @Test
    void rejectsUserScopedReplayRangeOlderThanItsRetentionWindow() {
        MonitorDataDeletionRequest request = request("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z");
        request.setUserId("user-1");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.preview("demo", request));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(clickHouse, jobMapper);
    }

    @Test
    void eventSnapshotUsesPersistedThroughJobStartCutoff() throws Exception {
        MonitorDataDeletionJob job = deletionJob(null);
        when(clickHouse.queryForList(any(String.class), any(Object[].class))).thenReturn(java.util.List.of());
        var select = MonitorDataDeletionService.class.getDeclaredMethod(
                "selectEventBatch", String.class, MonitorDataDeletionJob.class, String.class);
        select.setAccessible(true);

        select.invoke(service, "behavior_event", job, "cursor");

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("received_at<=?"));
        assertEquals(new Timestamp(job.getStartedAt().getTime()), args.getValue()[3]);
        assertEquals("cursor", args.getValue()[4]);
    }

    @Test
    void workerInitializesSnapshotTimeForLegacyQueuedJobs() {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(41L);
        job.setProjectId(1L);
        job.setProjectKey("demo-web");
        job.setRangeStart(java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        job.setRangeEnd(java.util.Date.from(Instant.parse("2026-10-02T00:00:00Z")));
        job.setStatus("QUEUED");
        job.setStage("SNAPSHOT_ERROR_EVENTS");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.claim(41L)).thenReturn(1);
        when(clickHouse.queryForList(any(String.class), any(Object[].class))).thenReturn(java.util.List.of());
        ReflectionTestUtils.setField(service, "workerEnabled", true);

        service.processOneBatch();

        assertTrue(job.getStartedAt() != null);
        assertEquals("SNAPSHOT_PERFORMANCE_EVENTS", job.getStage());
        assertEquals("QUEUED", job.getStatus());
        assertNull(job.getLeaseUntil());
        verify(jobMapper, atLeastOnce()).updateById(job);
    }

    @Test
    void userScopedEventSnapshotBindsSessionLookupAndCursorAfterCutoff() throws Exception {
        MonitorDataDeletionJob job = deletionJob("user-1");
        when(clickHouse.queryForList(any(String.class), any(Object[].class))).thenReturn(java.util.List.of());
        var select = MonitorDataDeletionService.class.getDeclaredMethod(
                "selectEventBatch", String.class, MonitorDataDeletionJob.class, String.class);
        select.setAccessible(true);

        select.invoke(service, "behavior_event", job, "cursor");

        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(any(String.class), args.capture());
        assertEquals(new Timestamp(job.getStartedAt().getTime()), args.getValue()[3]);
        assertEquals("user-1", args.getValue()[4]);
        assertEquals("demo-web", args.getValue()[5]);
        assertEquals(new Timestamp(job.getStartedAt().getTime()), args.getValue()[8]);
        assertEquals("user-1", args.getValue()[9]);
        assertEquals("cursor", args.getValue()[10]);
    }

    @Test
    void userScopedReplaySnapshotExcludesIndexesCreatedAfterJobStart() throws Exception {
        MonitorDataDeletionJob job = deletionJob("user-1");
        MonitorDataDeletionItem replayEvent = new MonitorDataDeletionItem();
        replayEvent.setId(9L);
        replayEvent.setItemValue("event-1");
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of(replayEvent));
        when(replayMapper.selectOne(any())).thenReturn(null);
        var snapshot = MonitorDataDeletionService.class.getDeclaredMethod(
                "snapshotReplayIndexBatch", MonitorDataDeletionJob.class);
        snapshot.setAccessible(true);

        snapshot.invoke(service, job);

        org.mockito.ArgumentCaptor<Wrapper<MonitorReplay>> wrapper = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(replayMapper).selectOne(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("create_time <="));
        assertTrue(((AbstractWrapper<?, ?, ?>) wrapper.getValue()).getParamNameValuePairs()
                .containsValue(job.getStartedAt()));
    }

    @Test
    void executeRejectsExpiredPreviewToken() {
        MonitorDataDeletionJob preview = new MonitorDataDeletionJob();
        preview.setId(3L);
        preview.setProjectId(1L);
        preview.setStatus("PREVIEW");
        preview.setPreviewToken("token");
        preview.setCreateTime(java.util.Date.from(Instant.now().minusSeconds(1801)));
        when(jobMapper.selectById(anyLong())).thenReturn(preview);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.execute("demo", 3L, "token"));
        assertEquals(409, error.getStatusCode().value());
        verify(jobMapper, never()).updateById(any(MonitorDataDeletionJob.class));
    }

    @Test
    void userDeletionSnapshotsSessionRowsAndKeepsTheSnapshotCutoff() {
        MonitorDataDeletionJob job = deletionJob("release-health-user");
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "selectEventBatch", "behavior_event", job, "");

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("session_id IN (SELECT session_id FROM monitor.behavior_event"));
        assertTrue(sql.getValue().contains("received_at<=?"));
        assertTrue(sql.getValue().contains("JSONExtractString(payload,'data','action')='start'"));
        assertEquals("release-health-user", args.getValue()[4]);
        assertEquals("release-health-user", args.getValue()[9]);
        assertEquals(11, args.getValue().length);
    }

    @Test
    void userDeletionPreviewIncludesAssociatedSessionRows() {
        when(clickHouse.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(3L);
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();

        Long count = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "countEvents", "behavior_event", "demo-web", from, to, "release-health-user");

        assertEquals(3L, count);
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForObject(sql.capture(), org.mockito.ArgumentMatchers.eq(Long.class), args.capture());
        assertTrue(sql.getValue().contains("session_id IN (SELECT session_id FROM monitor.behavior_event"));
        assertEquals("release-health-user", args.getValue()[3]);
        assertEquals("release-health-user", args.getValue()[7]);
        assertEquals(8, args.getValue().length);
    }

    @Test
    void spanDeletionSnapshotUsesProjectDateUserAndJobStartCutoff() {
        MonitorDataDeletionJob job = deletionJob("user-1");
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "selectEventBatch", "span_event", job, "cursor");

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("FROM monitor.span_event WHERE project_id=? AND event_time>=? AND event_time<?"));
        assertTrue(sql.getValue().contains("received_at<=?"));
        assertTrue(sql.getValue().contains("session_id IN (SELECT session_id FROM monitor.behavior_event"));
        assertEquals("demo-web", args.getValue()[0]);
        assertEquals(new Timestamp(job.getRangeStart().getTime()), args.getValue()[1]);
        assertEquals(new Timestamp(job.getRangeEnd().getTime()), args.getValue()[2]);
        assertEquals(new Timestamp(job.getStartedAt().getTime()), args.getValue()[3]);
        assertEquals("user-1", args.getValue()[4]);
        assertEquals("cursor", args.getValue()[10]);

        when(clickHouse.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        Long count = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                service, "countEvents", "span_event", "demo-web", job.getRangeStart().toInstant(),
                job.getRangeEnd().toInstant(), job.getUserId());
        assertEquals(2L, count);
        verify(clickHouse).queryForObject(argThat(query -> query.contains("FROM monitor.span_event")),
                org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class));
    }

    @Test
    void profileAndSpanSnapshotStagesIncludeSpanBeforeReplay() {
        MonitorDataDeletionJob job = deletionJob(null);
        job.setId(41L);
        job.setStatus("QUEUED");
        job.setStage("SNAPSHOT_PROFILE_EVENTS");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.claim(41L)).thenReturn(1);
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        ReflectionTestUtils.setField(service, "workerEnabled", true);

        service.processOneBatch();
        assertEquals("SNAPSHOT_SPAN_EVENTS", job.getStage());
        service.processOneBatch();
        assertEquals("SNAPSHOT_REPLAY_EVENTS", job.getStage());
    }

    @Test
    void profileAndSpanDeleteStagesIncludeSpanBeforePerformance() {
        MonitorDataDeletionJob job = deletionJob(null);
        job.setId(42L);
        job.setStatus("QUEUED");
        job.setStage("DELETE_PROFILE");
        when(jobMapper.selectOne(any())).thenReturn(job);
        when(jobMapper.claim(42L)).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of());
        when(itemMapper.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(service, "workerEnabled", true);

        service.processOneBatch();
        assertEquals("DELETE_SPAN_EVENT", job.getStage());
        service.processOneBatch();
        assertEquals("DELETE_PERFORMANCE", job.getStage());
    }

    @Test
    void repeatedErrorSnapshotKeepsFirstCountUnderStableIdempotencyKey() throws Exception {
        Map<String, MonitorDataDeletionItem> storedItems = new HashMap<>();
        when(itemMapper.insert(any(MonitorDataDeletionItem.class))).thenAnswer(invocation -> {
            MonitorDataDeletionItem item = invocation.getArgument(0);
            String key = item.getJobId() + ":" + item.getItemType() + ":" + item.getIdempotencyKey();
            if (storedItems.putIfAbsent(key, item) != null) {
                throw new org.springframework.dao.DuplicateKeyException("synthetic idempotency conflict");
            }
            return 1;
        });
        var snapshot = MonitorDataDeletionService.class.getDeclaredMethod(
                "saveErrorHourlySnapshot", Long.class, String.class, String.class);
        snapshot.setAccessible(true);

        snapshot.invoke(service, 41L, "synthetic-event", "[[1790931600,\"synthetic-fingerprint\",1]]");
        snapshot.invoke(service, 41L, "synthetic-event", "[[1790931600,\"synthetic-fingerprint\",2]]");

        MonitorDataDeletionItem stored = storedItems.values().stream()
                .filter(item -> "ERROR_HOURLY_DELTA".equals(item.getItemType())).findFirst().orElseThrow();
        assertEquals(64, stored.getIdempotencyKey().length());
        assertEquals(1, new ObjectMapper().readTree(stored.getItemValue()).path("eventCount").asInt());
        verify(itemMapper, times(6)).insert(any(MonitorDataDeletionItem.class));
    }

    @Test
    void completesDeletionAfterHourlyReconciliationAndFinalRedisCleanup() throws Exception {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(41L);
        job.setStatus("RUNNING");
        job.setStage("CLEAN_REDIS_DEDUP");
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of());
        when(itemMapper.selectCount(any())).thenReturn(1L);

        var cleanup = MonitorDataDeletionService.class.getDeclaredMethod(
                "cleanRedisDedupBatch", MonitorDataDeletionJob.class);
        cleanup.setAccessible(true);
        cleanup.invoke(service, job);

        verify(auditService).complete(job);
        verify(jobMapper, never()).updateById(job);
    }

    @Test
    void finalRedisCleanupDeletesSpanEventDeduplicationKey() throws Exception {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setId(42L);
        job.setStatus("RUNNING");
        job.setStage("CLEAN_REDIS_DEDUP");
        MonitorDataDeletionItem spanItem = new MonitorDataDeletionItem();
        spanItem.setId(1L);
        spanItem.setItemType("EVENT_ID_SPAN_EVENT");
        spanItem.setItemValue("span-event-1");
        when(itemMapper.selectCount(any())).thenReturn(1L);
        when(itemMapper.selectList(any())).thenReturn(java.util.List.of(spanItem));

        ReflectionTestUtils.invokeMethod(service, "cleanRedisDedupBatch", job);

        assertTrue(MonitorDataDeletionService.REDIS_DEDUP_EVENT_TYPES.contains("EVENT_ID_SPAN_EVENT"));
        verify(redis).delete("monitor:event:processed:span-event-1");
        verify(auditService).complete(job);
    }

    private MonitorDataDeletionRequest request(String from, String to) {
        MonitorDataDeletionRequest request = new MonitorDataDeletionRequest();
        request.setFrom(Instant.parse(from));
        request.setTo(Instant.parse(to));
        return request;
    }

    private MonitorDataDeletionJob deletionJob(String userId) {
        MonitorDataDeletionJob job = new MonitorDataDeletionJob();
        job.setProjectKey("demo-web");
        job.setRangeStart(java.util.Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        job.setRangeEnd(java.util.Date.from(Instant.parse("2026-10-02T00:00:00Z")));
        job.setStartedAt(java.util.Date.from(Instant.parse("2026-10-03T01:02:03Z")));
        job.setUserId(userId);
        return job;
    }
}
