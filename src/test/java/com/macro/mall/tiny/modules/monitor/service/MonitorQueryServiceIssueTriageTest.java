package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
}
