package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReleaseMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.dto.MonitorMetricFormulaRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorQueryServiceExploreFilterTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorQueryService service = new MonitorQueryService(
            clickHouse,
            mock(MonitorProjectMapper.class),
            mock(MonitorIssueMapper.class),
            mock(MonitorReleaseMapper.class),
            mock(MonitorReplayMapper.class),
            mock(MonitorAlertRuleMapper.class),
            mock(MonitorAlertRecordMapper.class)
    );

    @Test
    void appliesUserAndExactTagFiltersAsParametersWithinEachProject() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        String userId = "user' OR 1=1 --";
        String tagKey = "flow') OR 1=1 --";
        String tagValue = "checkout' OR project_id!='store-a";

        service.explore(project("store-a"), 24, null, null, null, null, null,
                userId, tagKey, tagValue, 100, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        String sql = sqlCaptor.getValue();
        List<Object> args = Arrays.asList(argsCaptor.getValue());
        assertEquals(6, count(sql, "project_id=?"));
        assertEquals(6, count(sql, "user_id=?"));
        assertEquals(6, count(sql, "JSONExtractRaw(payload,'data','tags',?)=?"));
        assertFalse(sql.contains(userId));
        assertFalse(sql.contains(tagKey));
        assertFalse(sql.contains(tagValue));
        for (int table = 0; table < 6; table++) {
            int base = table * 7;
            assertEquals("store-a", args.get(base));
            assertEquals(userId, args.get(base + 2));
            assertEquals(tagKey, args.get(base + 3));
            assertEquals(tagValue, args.get(base + 4));
            assertEquals(tagKey, args.get(base + 5));
            assertEquals(tagValue, args.get(base + 6));
        }
    }

    @Test
    void emptyFiltersKeepExistingProjectScopedQueryShape() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.explore(project("store-a"), 24, null, null, null, null, null,
                null, null, null, 100, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        String sql = sqlCaptor.getValue();
        List<Object> args = Arrays.asList(argsCaptor.getValue());
        assertEquals(6, count(sql, "project_id=?"));
        assertFalse(sql.contains("user_id=?"));
        assertFalse(sql.contains("JSONExtractRaw(payload,'data','tags'"));
        assertEquals(14, args.size());
        for (int table = 0; table < 6; table++) {
            assertEquals("store-a", args.get(table * 2));
        }
        assertEquals(101, args.get(12));
        assertEquals(0, args.get(13));
    }

    @Test
    void loadsDistinctUserIdsBySignalWithProjectAndTimeFiltersAndHardLimit() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("signal", "error", "user_id", "u1"),
                Map.of("signal", "performance", "user_id", "u2")));

        var users = service.exploreUniqueUsersBySignal(project("store-a"), 24, "production", "web-1",
                "0123456789abcdef0123456789abcdef", "level:error", "u1", "region", "east");

        assertEquals(java.util.Set.of("u1"), users.get("error"));
        assertEquals(java.util.Set.of("u2"), users.get("performance"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("GROUP BY signal,user_id LIMIT ?"));
        assertTrue(sqlCaptor.getValue().contains("user_id!=''"));
        assertEquals(MonitorQueryService.EXPLORE_UNIQUE_USER_LIMIT + 1,
                argsCaptor.getValue()[argsCaptor.getValue().length - 1]);
        assertEquals(6, count(sqlCaptor.getValue(), "project_id=?"));
        assertEquals(6, count(sqlCaptor.getValue(), "user_id=?"));
    }

    @Test
    void rejectsClickHouseUniqueUserResultAboveLimit() {
        List<java.util.Map<String, Object>> rows = java.util.stream.IntStream
                .range(0, MonitorQueryService.EXPLORE_UNIQUE_USER_LIMIT + 1)
                .mapToObj(i -> java.util.Map.<String, Object>of("signal", "error", "user_id", "u" + i))
                .toList();
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

        org.springframework.web.server.ResponseStatusException exception = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.exploreUniqueUsersBySignal(project("store-a"), 24, null, null, null,
                        null, null, null, null));

        assertEquals(422, exception.getStatusCode().value());
    }

    @Test
    void parsesFieldFiltersAndKeepsValuesOutOfSql() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        String query = "environment:production release:\"web 1.2\" user.id:\"user' OR 1=1 --\" tag.region:cn-east level:error";

        service.explore(project("store-a"), 24, null, null, null, null, query,
                null, null, null, 100, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        String sql = sqlCaptor.getValue();
        List<Object> args = Arrays.asList(argsCaptor.getValue());
        assertEquals(6, count(sql, "environment=?"));
        assertEquals(6, count(sql, "release=?"));
        assertEquals(6, count(sql, "user_id=?"));
        assertEquals(6, count(sql, "JSONExtractRaw(payload,'data','tags',?)=?"));
        assertEquals(6, count(sql, "JSONExtractString(payload,'data','level')=?"));
        assertFalse(sql.contains("user' OR 1=1 --"));
        for (int table = 0; table < 6; table++) {
            int base = table * 10;
            assertEquals("store-a", args.get(base));
            assertEquals("production", args.get(base + 2));
            assertEquals("web 1.2", args.get(base + 3));
            assertEquals("user' OR 1=1 --", args.get(base + 4));
            assertEquals("region", args.get(base + 5));
            assertEquals("cn-east", args.get(base + 6));
            assertEquals("region", args.get(base + 7));
            assertEquals("cn-east", args.get(base + 8));
            assertEquals("error", args.get(base + 9));
        }
    }

    @Test
    void compilesOrGroupsIntoBoundSqlPredicates() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.explore(project("store-a"), 24, null, null, null, null,
                "(environment:production OR release:\"staging build\") level:error",
                null, null, null, 100, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        String sql = sqlCaptor.getValue();
        List<Object> args = Arrays.asList(argsCaptor.getValue());
        assertEquals(6, count(sql, "(environment=? OR release=?)"));
        assertEquals(6, count(sql, "JSONExtractString(payload,'data','level')=?"));
        assertFalse(sql.contains("staging build"));
        for (int table = 0; table < 6; table++) {
            int base = table * 5;
            assertEquals("store-a", args.get(base));
            assertEquals("production", args.get(base + 2));
            assertEquals("staging build", args.get(base + 3));
            assertEquals("error", args.get(base + 4));
        }
    }

    @Test
    void aggregatesFilteredEventsByWhitelistedDimensionWithBoundValues() {
        when(clickHouse.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(java.util.Map.of("value", "production", "count", 4L)));

        var result = service.exploreAggregate(project("store-a"), 24, null, null, null, null,
                "environment:production", null, null, null, "environment", "count", "value");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        assertEquals(6, count(sqlCaptor.getValue(), "FROM monitor."));
        assertTrue(sqlCaptor.getValue().contains("GROUP BY group_value ORDER BY count DESC,value ASC LIMIT 100"));
        assertFalse(sqlCaptor.getValue().contains("production"));
        assertEquals(18, argsCaptor.getValue().length);
        assertEquals("store-a", argsCaptor.getValue()[0]);
        assertEquals("production", argsCaptor.getValue()[2]);
        assertEquals("environment", result.groupBy());
        assertEquals("count", result.aggregation());
        assertEquals(new com.macro.mall.tiny.modules.monitor.dto.MonitorExploreAggregationResult.Bucket("production", 4, null),
                result.buckets().get(0));
    }

    @Test
    void computesPerformancePercentilesOverOnlyEventsWithNumericValues() {
        when(clickHouse.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(java.util.Map.of("value", "production", "count", 3L, "aggregate_value", 1250.0)));

        var result = service.exploreAggregate(project("store-a"), 24, "performance", null, null, null,
                null, null, null, null, "environment", "p95", "value");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("quantile(0.95)(aggregate_value)"));
        assertTrue(sqlCaptor.getValue().contains("JSONHas(payload,'data','value')"));
        assertEquals(2, argsCaptor.getValue().length);
        assertEquals(1250.0, result.buckets().get(0).aggregateValue());
    }

    @Test
    void computesProjectAndEnvironmentScopedMetricFormulaPerHour() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                java.util.Map.of("bucket", "2026-10-03 10:00:00", "name", "checkout.completed", "metricType", "counter", "value", 20.0),
                java.util.Map.of("bucket", "2026-10-03 10:00:00", "name", "checkout.started", "metricType", "counter", "value", 80.0),
                java.util.Map.of("bucket", "2026-10-03 11:00:00", "name", "checkout.completed", "metricType", "counter", "value", 2.0)
        ));
        MonitorMetricFormulaRequest request = new MonitorMetricFormulaRequest();
        request.setMetricNames(List.of("checkout.completed", "checkout.started"));
        request.setFormula("a / b * 100");
        request.setEnvironment("production");

        var result = service.exploreMetricFormula(project("store-a"), request);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("FROM monitor.metric_event"));
        assertTrue(sqlCaptor.getValue().contains("toStartOfHour(event_time)"));
        assertTrue(sqlCaptor.getValue().contains("JSONExtractString(payload,'data','name') IN (?,?)"));
        assertTrue(sqlCaptor.getValue().contains("project_id=?"));
        assertEquals("store-a", argsCaptor.getValue()[0]);
        assertEquals("checkout.completed", argsCaptor.getValue()[2]);
        assertEquals("checkout.started", argsCaptor.getValue()[3]);
        assertEquals("production", argsCaptor.getValue()[4]);
        assertEquals("a / b * 100", result.formula());
        assertEquals(1, result.points().size());
        assertEquals("2026-10-03 10:00:00", result.points().get(0).bucket());
        assertEquals(25.0, result.points().get(0).value());
    }

    @Test
    void countsUniqueUsersAndKeepsDistinctCountSeparateFromSampleCount() {
        when(clickHouse.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(java.util.Map.of("value", "production", "count", 7L, "aggregate_value", 3L)));

        var result = service.exploreAggregate(project("store-a"), 24, "error", null, null, null,
                null, null, null, null, "environment", "count_unique", "user");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).queryForList(sqlCaptor.capture(), any(Object[].class));
        assertTrue(sqlCaptor.getValue().contains("uniqExactIf(aggregate_value,aggregate_value!='')"));
        assertEquals(7L, result.buckets().get(0).count());
        assertEquals(3.0, result.buckets().get(0).aggregateValue());
    }

    @Test
    void rejectsUnsafeAggregationDimensions() {
        assertThrows(IllegalArgumentException.class, () -> service.exploreAggregate(project("store-a"), 24,
                null, null, null, null, null, null, null, null, "environment;DROP TABLE x", "count", "value"));
        assertThrows(IllegalArgumentException.class, () -> service.exploreAggregate(project("store-a"), 24,
                "error", null, null, null, null, null, null, null, "signal", "avg", "value"));
    }

    private MonitorProject project(String projectKey) {
        MonitorProject project = new MonitorProject();
        project.setProjectKey(projectKey);
        return project;
    }

    private int count(String value, String target) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(target, from)) >= 0) {
            count++;
            from += target.length();
        }
        return count;
    }
}
