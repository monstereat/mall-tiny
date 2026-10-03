package com.macro.mall.tiny.modules.monitor.dto;

import java.util.Date;

public record MonitorIssueActivityView(
        Long id,
        String activityType,
        Long actorAdminId,
        String actorName,
        String commentText,
        String previousStatus,
        String newStatus,
        Date createTime) {
}
