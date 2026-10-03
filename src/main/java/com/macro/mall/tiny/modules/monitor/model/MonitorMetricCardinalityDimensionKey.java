package com.macro.mall.tiny.modules.monitor.model;

import lombok.Data;

@Data
public class MonitorMetricCardinalityDimensionKey {
    private Long projectId;
    private String metricName;
    private String dimensionKey;
}
