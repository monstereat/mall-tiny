package com.macro.mall.tiny.modules.monitor.dto;

import java.util.List;

public record MonitorBusinessAnalyticsResult(
        Summary summary,
        List<PageBucket> pages,
        List<EventBucket> events,
        List<HourlyBucket> hourly,
        boolean pagesTruncated,
        boolean eventsTruncated,
        String rankingNote) {

    public record Summary(
            long pv,
            long knownUserUv,
            long sessions,
            long businessEventCount,
            double visibleDwellMs,
            double avgVisibleDwellMs,
            long excludedSampled) {
    }

    public record PageBucket(
            String pageUrl,
            long pageViews,
            long uniqueUsers,
            long sessions,
            double visibleDwellMs,
            double avgVisibleDwellMs) {
    }

    public record EventBucket(
            String event,
            long eventCount,
            long uniqueUsers,
            long sessions) {
    }

    public record HourlyBucket(String bucket, long pageViews, long eventCount) {
    }
}
