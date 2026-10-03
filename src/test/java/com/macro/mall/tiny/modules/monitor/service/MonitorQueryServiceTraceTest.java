package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceOverview;
import com.macro.mall.tiny.modules.monitor.dto.MonitorTraceSpan;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReleaseMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorQueryServiceTraceTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorQueryService service = new MonitorQueryService(
            clickHouse,
            mock(MonitorProjectMapper.class),
            mock(MonitorIssueMapper.class),
            mock(MonitorReleaseMapper.class),
            mock(MonitorReplayMapper.class),
            mock(MonitorAlertRuleMapper.class),
            mock(MonitorAlertRecordMapper.class));

    @Test
    void aggregatesRealTraceEventsAcrossAllSignalsWithExactFiltersAndStablePaging() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("store-a");
        when(clickHouse.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "traceId", "trace-1",
                "firstEventAt", Timestamp.from(Instant.parse("2026-10-03T10:00:00Z")),
                "lastEventAt", Timestamp.from(Instant.parse("2026-10-03T10:01:00Z")),
                "eventCount", 6L,
                "environment", "production",
                "release", "web-42",
                "signalTypes", "behavior,error,metric,performance,profile,replay,span")));

        IPage<MonitorTraceOverview> page = service.traces(project, 24, "production", "web-42", 2, 10);

        assertEquals(1, page.getTotal());
        assertEquals(2, page.getCurrent());
        assertEquals(10, page.getSize());
        MonitorTraceOverview trace = page.getRecords().get(0);
        assertEquals("trace-1", trace.traceId());
        assertEquals(6, trace.eventCount());
        assertEquals("production", trace.environment());
        assertEquals("web-42", trace.release());
        assertEquals("behavior,error,metric,performance,profile,replay,span", trace.signalTypes());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForObject(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture());
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        for (String sql : sqlCaptor.getAllValues()) {
            assertTrue(sql.contains("monitor.error_event"));
            assertTrue(sql.contains("monitor.performance_event"));
            assertTrue(sql.contains("monitor.behavior_event"));
            assertTrue(sql.contains("monitor.replay_event"));
            assertTrue(sql.contains("monitor.metric_event"));
            assertTrue(sql.contains("monitor.profile_event"));
            assertTrue(sql.contains("monitor.span_event"));
            assertTrue(sql.contains("trace_id!=''"));
            assertTrue(sql.contains("environment=?"));
            assertTrue(sql.contains("release=?"));
        }
        assertTrue(sqlCaptor.getAllValues().get(1).contains("ORDER BY lastEventAt DESC,traceId DESC LIMIT ? OFFSET ?"));
        assertEquals(28, argsCaptor.getAllValues().get(0).length);
        assertEquals(30, argsCaptor.getAllValues().get(1).length);
        assertEquals(10L, argsCaptor.getAllValues().get(1)[28]);
        assertEquals(10L, argsCaptor.getAllValues().get(1)[29]);
    }

    @Test
    void traceSpansAreScopedToProjectAndTraceAndReturnSpanFields() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("store-a");
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "traceId", "trace-1",
                "spanId", "0123456789abcdef",
                "parentSpanId", "fedcba9876543210",
                "op", "otel.server",
                "serviceName", "checkout",
                "description", "GET /checkout",
                "startTime", 1_791_026_400_000L,
                "durationMs", 22.5,
                "status", "error",
                "statusCode", 503L)));

        MonitorTraceSpan span = service.traceSpans(project, "trace-1").get(0);

        assertEquals("trace-1", span.traceId());
        assertEquals("0123456789abcdef", span.spanId());
        assertEquals("fedcba9876543210", span.parentSpanId());
        assertEquals("server", span.source());
        assertEquals("checkout", span.serviceName());
        assertEquals("server", span.kind());
        assertEquals("otel.server", span.op());
        assertEquals("GET /checkout", span.description());
        assertEquals(1_791_026_400_000L, span.startTime());
        assertEquals(22.5, span.durationMs());
        assertEquals("error", span.status());
        assertEquals(503L, span.statusCode());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("FROM monitor.span_event WHERE project_id=? AND trace_id=?"));
        assertTrue(sql.getValue().contains("JSONExtractString(payload,'data','serviceName') AS serviceName"));
        assertTrue(!sql.getValue().contains("attributes") && !sql.getValue().contains("tags"));
        assertTrue(sql.getValue().contains("ORDER BY startTime,spanId LIMIT 2000"));
        assertEquals("store-a", args.getValue()[0]);
        assertEquals("trace-1", args.getValue()[1]);
    }

    @Test
    void traceWithoutProjectScopedSpansReturnsEmptyWithoutCrossProjectLookup() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("store-a");
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertTrue(service.traceSpans(project, "foreign-trace").isEmpty());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(anyString(), args.capture());
        assertEquals("store-a", args.getValue()[0]);
        assertEquals("foreign-trace", args.getValue()[1]);
    }

    @Test
    void clampsPageValuesAndHoursToSafeBounds() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("store-a");
        when(clickHouse.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        IPage<MonitorTraceOverview> page = service.traces(project, 0, null, null, 0, 1000);

        assertEquals(1, page.getCurrent());
        assertEquals(100, page.getSize());
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForObject(anyString(), eq(Long.class), argsCaptor.capture());
        Timestamp since = (Timestamp) argsCaptor.getValue()[1];
        assertTrue(since.toInstant().isAfter(Instant.now().minusSeconds(2 * 60 * 60L)));
    }
}
