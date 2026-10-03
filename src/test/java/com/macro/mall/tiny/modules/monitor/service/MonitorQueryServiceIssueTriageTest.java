package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRecordMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorAlertRuleMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorProjectMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReleaseMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorReplayMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorQueryServiceIssueTriageTest {

    private final JdbcTemplate clickHouse = mock(JdbcTemplate.class);
    private final MonitorProjectMapper projectMapper = mock(MonitorProjectMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorQueryService service = new MonitorQueryService(
            clickHouse,
            projectMapper,
            issueMapper,
            mock(MonitorReleaseMapper.class),
            mock(MonitorReplayMapper.class),
            mock(MonitorAlertRuleMapper.class),
            mock(MonitorAlertRecordMapper.class)
    );

    @Test
    void issueListIncludesNewRegressionAndReleaseScopedTwentyFourHourTrend() {
        MonitorProject project = new MonitorProject();
        project.setId(31L);
        project.setProjectKey("demo-web");
        when(projectMapper.selectById(31L)).thenReturn(project);

        MonitorIssue issue = new MonitorIssue();
        issue.setId(5L);
        issue.setProjectId(31L);
        issue.setFingerprint("fingerprint-a");
        issue.setStatus("unresolved");
        issue.setCreateTime(new Date(System.currentTimeMillis() - 60 * 60_000L));
        issue.setRegressedAt(new Date());
        Page<MonitorIssue> result = new Page<>(1, 20);
        result.setRecords(List.of(issue));
        when(issueMapper.selectPage(any(Page.class), any())).thenReturn(result);
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "fingerprint", "fingerprint-a",
                "current_count", 7L,
                "previous_count", 3L
        )));

        IPage<MonitorIssue> page = service.issues(31L, 1, 20, null, 720, "release-2");

        MonitorIssue triaged = page.getRecords().get(0);
        assertTrue(triaged.isNewIssue());
        assertEquals(7L, triaged.getEventsLast24h());
        assertEquals(3L, triaged.getEventsPrevious24h());
        assertTrue(triaged.getRegressedAt() != null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse).queryForList(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("release=?"));
        assertFalse(sql.getValue().contains("release-2"));
        assertEquals("demo-web", args.getValue()[3]);
        assertEquals("fingerprint-a", args.getValue()[4]);
        assertEquals("release-2", args.getValue()[6]);
    }

    @Test
    void oldIssueIsNotMarkedNewAndMissingTrendCountsDefaultToZero() {
        MonitorProject project = new MonitorProject();
        project.setId(31L);
        project.setProjectKey("demo-web");
        when(projectMapper.selectById(31L)).thenReturn(project);

        MonitorIssue issue = new MonitorIssue();
        issue.setFingerprint("fingerprint-old");
        issue.setCreateTime(new Date(System.currentTimeMillis() - 3 * 24 * 60 * 60_000L));
        Page<MonitorIssue> result = new Page<>(1, 20);
        result.setRecords(List.of(issue));
        when(issueMapper.selectPage(any(Page.class), any())).thenReturn(result);
        when(clickHouse.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        MonitorIssue triaged = service.issues(31L, 1, 20, null, 720, null).getRecords().get(0);

        assertFalse(triaged.isNewIssue());
        assertEquals(0L, triaged.getEventsLast24h());
        assertEquals(0L, triaged.getEventsPrevious24h());
    }

    @Test
    void issueSortUsesAllowlistedDescendingFieldsAndStableTieBreakers() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "issue-sort-test"),
                MonitorIssue.class
        );
        Page<MonitorIssue> emptyPage = new Page<>(1, 20);
        when(issueMapper.selectPage(any(Page.class), any())).thenReturn(emptyPage);

        service.issues(31L, 1, 20, null, 720, null, "lastSeen");
        service.issues(31L, 1, 20, null, 720, null, "eventCount");
        service.issues(31L, 1, 20, null, 720, null, "affectedUsers");
        service.issues(31L, 1, 20, null, 720, null, null);
        service.issues(31L, 1, 20, null, 720, null, "not-allowed");

        ArgumentCaptor<Wrapper<MonitorIssue>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper, times(5)).selectPage(any(Page.class), queryCaptor.capture());
        List<String> orderBy = queryCaptor.getAllValues().stream()
                .map(Wrapper::getSqlSegment)
                .toList();

        assertTrue(orderBy.get(0).contains("ORDER BY last_seen DESC,id DESC"));
        assertTrue(orderBy.get(1).contains("ORDER BY event_count DESC,last_seen DESC,id DESC"));
        assertTrue(orderBy.get(2).contains("ORDER BY affected_users DESC,last_seen DESC,id DESC"));
        assertTrue(orderBy.get(3).contains("ORDER BY last_seen DESC,id DESC"));
        assertTrue(orderBy.get(4).contains("ORDER BY last_seen DESC,id DESC"));
    }

    @Test
    void issueSearchAndEnvironmentFiltersAreComposedWithinProjectAndTimeRange() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "issue-search-test"),
                MonitorIssue.class
        );
        MonitorProject project = new MonitorProject();
        project.setId(31L);
        project.setProjectKey("demo-web");
        Page<MonitorIssue> result = new Page<>(1, 20);
        result.setRecords(List.of());
        when(clickHouse.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("fingerprint-in-prod"), List.of("fingerprint-message-match"));
        when(issueMapper.selectPage(any(Page.class), any())).thenReturn(result);

        service.issues(project, 1, 20, "unresolved", 168, "release-1", "lastSeen",
                "TypeError", "production");

        ArgumentCaptor<String> clickHouseSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> clickHouseArgs = ArgumentCaptor.forClass(Object[].class);
        verify(clickHouse, times(2)).queryForList(clickHouseSql.capture(), eq(String.class), clickHouseArgs.capture());
        assertTrue(clickHouseSql.getAllValues().get(0).contains("environment=?"));
        assertTrue(clickHouseSql.getAllValues().get(0).contains("release=?"));
        assertTrue(clickHouseSql.getAllValues().get(0).contains("project_id=? AND event_time>=?"));
        assertEquals("demo-web", clickHouseArgs.getAllValues().get(0)[0]);
        assertEquals("production", clickHouseArgs.getAllValues().get(0)[2]);
        assertEquals("release-1", clickHouseArgs.getAllValues().get(0)[3]);
        assertTrue(clickHouseSql.getAllValues().get(1).contains(
                "positionCaseInsensitiveUTF8(JSONExtractString(payload,'data','message'),?)>0"));
        assertTrue(clickHouseSql.getAllValues().get(1).contains("environment=?"));
        assertTrue(clickHouseSql.getAllValues().get(1).contains("release=?"));
        assertEquals("production", clickHouseArgs.getAllValues().get(1)[2]);
        assertEquals("release-1", clickHouseArgs.getAllValues().get(1)[3]);
        assertEquals("TypeError", clickHouseArgs.getAllValues().get(1)[4]);

        ArgumentCaptor<Wrapper<MonitorIssue>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).selectPage(any(Page.class), queryCaptor.capture());
        String sql = queryCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("project_id ="));
        assertTrue(sql.contains("fingerprint IN"));
        assertTrue(sql.contains("title LIKE"));
        assertTrue(sql.contains("status ="));
        assertTrue(sql.contains("latest_release ="));
        LambdaQueryWrapper<MonitorIssue> lambdaQuery = (LambdaQueryWrapper<MonitorIssue>) queryCaptor.getValue();
        assertTrue(lambdaQuery.getParamNameValuePairs().containsValue("%TypeError%"));
        assertTrue(lambdaQuery.getParamNameValuePairs().containsValue("release-1"));
    }

    @Test
    void issueEnvironmentFilterWithNoMatchesReturnsAnEmptyPageWithoutIssueQuery() {
        MonitorProject project = new MonitorProject();
        project.setId(31L);
        project.setProjectKey("demo-web");
        when(clickHouse.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());

        IPage<MonitorIssue> page = service.issues(project, 2, 20, null, 720, null, null,
                null, "missing-environment");

        assertEquals(2, page.getCurrent());
        assertEquals(0, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
        verify(issueMapper, org.mockito.Mockito.never()).selectPage(any(Page.class), any());
    }
}
