package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorExploreAggregationResult(String groupBy, String aggregation, String field, List<Bucket> buckets) {
    public record Bucket(String value, long count, Double aggregateValue) { }
}
