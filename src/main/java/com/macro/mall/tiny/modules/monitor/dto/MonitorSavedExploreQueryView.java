package com.macro.mall.tiny.modules.monitor.dto;

import java.util.Date;

public record MonitorSavedExploreQueryView(
        Long id,
        String name,
        MonitorSavedExploreQueryRequest criteria,
        Long createdBy,
        boolean canModify,
        Date createTime,
        Date updateTime
) { }
