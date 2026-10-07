package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorMetricFormulaResult(String formula, List<Point> points) {
    public record Point(String bucket, double value) { }
}
