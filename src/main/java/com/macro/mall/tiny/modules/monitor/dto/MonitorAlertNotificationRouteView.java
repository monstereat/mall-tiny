package com.macro.mall.tiny.modules.monitor.dto;

import java.util.Date;

public record MonitorAlertNotificationRouteView(
        Long id,
        String name,
        String webhookUrl,
        Date createTime,
        Date updateTime
) { }
