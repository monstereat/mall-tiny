package com.macro.mall.tiny.modules.monitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityPage;
import com.macro.mall.tiny.modules.monitor.dto.MonitorIssueActivityView;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueActivityMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorIssueMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssueActivity;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class MonitorIssueActivityService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_COMMENT_LENGTH = 2000;

    private final MonitorIssueActivityMapper activityMapper;
    private final MonitorIssueMapper issueMapper;
    private final MonitorProjectAccessService projectAccessService;

    public MonitorIssueActivityPage list(MonitorProject project, Long issueId, Long beforeId, int requestedLimit) {
        requireIssue(project, issueId);
        if (beforeId != null && beforeId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "beforeId must be positive");
        }
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit <= 0 ? DEFAULT_LIMIT : requestedLimit));
        var query = Wrappers.<MonitorIssueActivity>lambdaQuery()
                .eq(MonitorIssueActivity::getProjectId, project.getId())
                .eq(MonitorIssueActivity::getIssueId, issueId)
                .lt(beforeId != null, MonitorIssueActivity::getId, beforeId)
                .orderByDesc(MonitorIssueActivity::getId)
                .last("LIMIT " + (limit + 1));
        List<MonitorIssueActivity> rows = activityMapper.selectList(query);
        boolean hasMore = rows.size() > limit;
        List<MonitorIssueActivityView> records = rows.stream().limit(limit)
                .map(this::toView).toList();
        Long nextBeforeId = hasMore && !records.isEmpty() ? records.get(records.size() - 1).id() : null;
        return new MonitorIssueActivityPage(records, hasMore, nextBeforeId);
    }

    @Transactional
    public MonitorIssueActivityView addComment(MonitorProject project, Long issueId, String comment) {
        requireIssue(project, issueId);
        if (!StringUtils.hasText(comment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment must not be blank");
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment must be at most 2000 characters");
        }
        MonitorIssueActivity activity = newActivity(project, issueId, "comment");
        activity.setCommentText(trimmed);
        activityMapper.insert(activity);
        return toView(activity);
    }

    @Transactional
    public void recordStatusChange(MonitorProject project, Long issueId, String previousStatus, String newStatus) {
        if (java.util.Objects.equals(previousStatus, newStatus)) return;
        MonitorIssueActivity activity = newActivity(project, issueId, "status");
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        activityMapper.insert(activity);
    }

    private MonitorIssue requireIssue(MonitorProject project, Long issueId) {
        MonitorIssue issue = issueMapper.selectOne(Wrappers.<MonitorIssue>lambdaQuery()
                .eq(MonitorIssue::getProjectId, project.getId())
                .eq(MonitorIssue::getId, issueId)
                .last("LIMIT 1"));
        if (issue == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "issue not found");
        return issue;
    }

    private MonitorIssueActivity newActivity(MonitorProject project, Long issueId, String type) {
        MonitorIssueActivity activity = new MonitorIssueActivity();
        activity.setProjectId(project.getId());
        activity.setIssueId(issueId);
        activity.setActorAdminId(projectAccessService.currentAdminId());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String actorName = authentication == null ? null : authentication.getName();
        activity.setActorName(StringUtils.hasText(actorName) ? actorName.substring(0, Math.min(64, actorName.length())) : "admin");
        activity.setActivityType(type);
        activity.setCreateTime(new Date());
        return activity;
    }

    private MonitorIssueActivityView toView(MonitorIssueActivity activity) {
        return new MonitorIssueActivityView(activity.getId(), activity.getActivityType(),
                activity.getActorAdminId(), activity.getActorName(), activity.getCommentText(),
                activity.getPreviousStatus(), activity.getNewStatus(), activity.getCreateTime());
    }
}
