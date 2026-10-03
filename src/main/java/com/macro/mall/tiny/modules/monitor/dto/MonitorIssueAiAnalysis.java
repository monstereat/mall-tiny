package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorIssueAiAnalysis(
        String summary,
        String severity,
        double confidence,
        List<String> possibleCauses,
        List<String> recommendations,
        List<String> evidence,
        List<String> limitations) {
}
