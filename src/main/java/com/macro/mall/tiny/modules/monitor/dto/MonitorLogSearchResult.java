package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;
import java.util.Map;

public record MonitorLogSearchResult(String traceId, List<Entry> entries) {
    public record Entry(String timestamp, String line, Map<String, String> labels, Map<String, String> metadata) {
    }
}
