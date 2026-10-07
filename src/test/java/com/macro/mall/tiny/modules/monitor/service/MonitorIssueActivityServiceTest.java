package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueActivityMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssueActivity;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonitorIssueActivityServiceTest {

    private final MonitorIssueActivityMapper activityMapper = mock(MonitorIssueActivityMapper.class);
    private final MonitorIssueMapper issueMapper = mock(MonitorIssueMapper.class);
    private final MonitorProjectAccessService accessService = mock(MonitorProjectAccessService.class);
    private final MonitorIssueActivityService service = new MonitorIssueActivityService(
            activityMapper, issueMapper, accessService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listIsProjectAndIssueScopedUsesCursorAndCapsLimit() {
        initTableInfo("issue-activity-list-test");
        MonitorProject project = project();
        MonitorIssue issue = issue(12L, 42L);
        when(issueMapper.selectOne(any())).thenReturn(issue);
        MonitorIssueActivity first = activity(30L);
        MonitorIssueActivity second = activity(29L);
        when(activityMapper.selectList(any())).thenReturn(List.of(first, second, activity(28L)));

        var page = service.list(project, 12L, 31L, 2);

        assertEquals(2, page.records().size());
        assertTrue(page.hasMore());
        assertEquals(29L, page.nextBeforeId());
        assertEquals(30L, page.records().get(0).id());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MonitorIssueActivity>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(activityMapper).selectList(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("project_id ="));
        assertTrue(sql.contains("issue_id ="));
        assertTrue(sql.contains("id <"));
        assertTrue(query.getValue().getCustomSqlSegment().contains("ORDER BY id DESC LIMIT 3"));
    }

    @Test
    void listCapsRequestedLimitAtOneHundred() {
        initTableInfo("issue-activity-limit-test");
        when(issueMapper.selectOne(any())).thenReturn(issue(12L, 42L));
        when(activityMapper.selectList(any())).thenReturn(List.of());

        service.list(project(), 12L, null, 500);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MonitorIssueActivity>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(activityMapper).selectList(query.capture());
        assertTrue(query.getValue().getCustomSqlSegment().contains("LIMIT 101"));
    }

    @Test
    void commentIsTrimmedAndRecordsAuthenticatedActorForAuthorizedIssue() {
        initTableInfo("issue-activity-comment-test");
        MonitorProject project = project();
        when(issueMapper.selectOne(any())).thenReturn(issue(12L, 42L));
        when(accessService.currentAdminId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        var response = service.addComment(project, 12L, "  fixed by cache invalidation  ");

        ArgumentCaptor<MonitorIssueActivity> activity = ArgumentCaptor.forClass(MonitorIssueActivity.class);
        verify(activityMapper).insert(activity.capture());
        assertEquals(42L, activity.getValue().getProjectId());
        assertEquals(12L, activity.getValue().getIssueId());
        assertEquals(7L, activity.getValue().getActorAdminId());
        assertEquals("alice", activity.getValue().getActorName());
        assertEquals("comment", activity.getValue().getActivityType());
        assertEquals("fixed by cache invalidation", activity.getValue().getCommentText());
        assertTrue(response.createTime() != null);
    }

    @Test
    void commentCannotReadOrWriteIssueFromAnotherProject() {
        MonitorProject project = project();
        when(issueMapper.selectOne(any())).thenReturn(null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.addComment(project, 99L, "comment"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(activityMapper, never()).insert(any(MonitorIssueActivity.class));
    }

    @Test
    void statusChangesAreRecordedButNoOpStatusesAreNot() {
        when(accessService.currentAdminId()).thenReturn(8L);
        MonitorProject project = project();

        service.recordStatusChange(project, 12L, "unresolved", "resolved");
        service.recordStatusChange(project, 12L, "resolved", "resolved");

        ArgumentCaptor<MonitorIssueActivity> activity = ArgumentCaptor.forClass(MonitorIssueActivity.class);
        verify(activityMapper).insert(activity.capture());
        assertEquals("status", activity.getValue().getActivityType());
        assertEquals("unresolved", activity.getValue().getPreviousStatus());
        assertEquals("resolved", activity.getValue().getNewStatus());
    }

    @Test
    void rejectsInvalidCommentLengths() {
        MonitorProject project = project();
        when(issueMapper.selectOne(any())).thenReturn(issue(12L, 42L));
        when(accessService.currentAdminId()).thenReturn(7L);

        assertThrows(ResponseStatusException.class,
                () -> service.addComment(project, 12L, "x".repeat(2001)));
        verify(activityMapper, never()).insert(any(MonitorIssueActivity.class));
    }

    private void initTableInfo(String namespace) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), namespace), MonitorIssueActivity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), namespace + "-issue"), MonitorIssue.class);
    }

    private MonitorProject project() {
        MonitorProject project = new MonitorProject();
        project.setId(42L);
        return project;
    }

    private MonitorIssue issue(Long id, Long projectId) {
        MonitorIssue issue = new MonitorIssue();
        issue.setId(id);
        issue.setProjectId(projectId);
        return issue;
    }

    private MonitorIssueActivity activity(Long id) {
        MonitorIssueActivity item = new MonitorIssueActivity();
        item.setId(id);
        item.setProjectId(42L);
        item.setIssueId(12L);
        item.setActivityType("comment");
        item.setActorName("user");
        item.setCreateTime(new Date());
        return item;
    }
}
