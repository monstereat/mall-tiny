package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitorPerformanceSamplesTest {
    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorQueryService service = new MonitorQueryService(clickHouse, null,
            mock(MonitorIssueMapper.class), null, null, null, null);

    @Test
    void performanceSummaryTrendRecentAndResourceQueriesUseLatestScopedSamples() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        var result = service.performance(project(), 24, "production", "web-1");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse, times(4)).queryForList(sql.capture(), args.capture());
        for (String statement : sql.getAllValues()) {
            assertTrue(statement.contains("JSONExtractString(e.payload,'data','metricId')"));
            assertTrue(statement.contains("JSONExtractUInt(e.payload,'data','metricUpdate')"));
            assertTrue(statement.contains("GROUP BY e.project_id"));
            assertTrue(statement.contains("FROM (SELECT * FROM monitor.performance_event WHERE project_id=?"));
            assertTrue(statement.contains("e.event_id"));
        }
        assertTrue(sql.getAllValues().get(3).contains("NULL,quantileIf(0.75)"));
        assertTrue(sql.getAllValues().get(3).contains("cacheUnknownCount"));
        assertTrue(result.containsKey("resources"));
        assertTrue(result.containsKey("sampleBasis"));
        for (Object[] parameters : args.getAllValues()) assertEquals("project-a", parameters[0]);
    }

    @Test
    void numericExploreUsesLatestPerformanceReportsWhileRawCountsKeepEventSemantics() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        service.exploreAggregate(project(), 24, "performance", null, null, null,
                null, null, null, null, "environment", "p95", "value");
        service.exploreAggregate(project(), 24, "performance", null, null, null,
                null, null, null, null, "environment", "count", "value");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("metricUpdate"));
        assertFalse(sql.getAllValues().get(1).contains("metricUpdate"));
    }

    @Test
    void dashboardUsesTheSameLatestReportBasisAsThePerformancePage() {
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        service.dashboard(project(), 24, "production", "web-1");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(1).contains("metricUpdate"));
        assertTrue(sql.getAllValues().get(1).contains("e.project_id"));
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setProjectKey("project-a");
        return project;
    }
}
