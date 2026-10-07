package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;
import java.util.Map;

public record MonitorExploreResult(List<Map<String, Object>> events, boolean hasMore, int limit) {
}
