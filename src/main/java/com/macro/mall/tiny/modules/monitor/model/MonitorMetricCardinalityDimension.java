package com.macro.mall.tiny.modules.monitor.model;

import lombok.Data;

import java.util.Date;

@Data
public class MonitorMetricCardinalityDimension {
    private Integer distinctValueCount;
    private Date lastSeen;
}
