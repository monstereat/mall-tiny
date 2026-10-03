package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/profiles")
@RequiredArgsConstructor
public class MonitorProfilingAdminController {
    private final MonitorAdminService adminService;
    private final MonitorProfileService profileService;

    @GetMapping
    public CommonResult<List<MonitorProfileService.ProfileSummary>> list(
            @PathVariable String projectKey,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String release) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(profileService.list(project, hours, environment, release));
    }

    @GetMapping("/{eventId}")
    public CommonResult<MonitorProfileService.ProfileDetail> detail(
            @PathVariable String projectKey, @PathVariable String eventId) {
        MonitorProject project = adminService.requireProject(projectKey);
        return CommonResult.success(profileService.get(project, eventId));
    }
}
