package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorBusinessAnalyticsResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorBusinessAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/business")
@RequiredArgsConstructor
public class MonitorBusinessAnalyticsController {

    private final MonitorAdminService adminService;
    private final MonitorBusinessAnalyticsService analyticsService;

    @GetMapping
    public CommonResult<MonitorBusinessAnalyticsResult> query(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String event) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(analyticsService.query(project, hours, environment, release, page, event));
    }
}
