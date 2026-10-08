package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorIssueActivityPage(
        List<MonitorIssueActivityView> records,
        boolean hasMore,
        Long nextBeforeId) {
}
