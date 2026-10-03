package com.macro.mall.tiny.modules.monitor.dto;

public record MonitorReleaseHealth(
        String release,
        String environment,
        long sessions,
        long crashedSessions,
        double crashFreeSessionsRate,
        long users,
        long crashedUsers,
        double crashFreeUsersRate,
        long unhandledErrors
) {
}
