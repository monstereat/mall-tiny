package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorUptimeCheckRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeCheck;
import com.macro.mall.tiny.modules.monitor.model.MonitorUptimeHistory;
import com.macro.mall.tiny.modules.monitor.service.MonitorUptimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/uptime")
@RequiredArgsConstructor
public class MonitorUptimeAdminController {

    private final MonitorUptimeService uptimeService;

    @GetMapping
    public CommonResult<List<MonitorUptimeCheck>> list(@PathVariable String projectKey) {
        return CommonResult.success(uptimeService.list(projectKey));
    }

    @PostMapping
    public CommonResult<MonitorUptimeCheck> create(
            @PathVariable String projectKey, @Valid @RequestBody MonitorUptimeCheckRequest request) {
        return CommonResult.success(uptimeService.create(projectKey, request));
    }

    @PutMapping("/{checkId}")
    public CommonResult<MonitorUptimeCheck> update(
            @PathVariable String projectKey, @PathVariable Long checkId,
            @Valid @RequestBody MonitorUptimeCheckRequest request) {
        return CommonResult.success(uptimeService.update(projectKey, checkId, request));
    }

    @DeleteMapping("/{checkId}")
    public CommonResult<Void> delete(@PathVariable String projectKey, @PathVariable Long checkId) {
        uptimeService.delete(projectKey, checkId);
        return CommonResult.success(null);
    }

    @GetMapping("/{checkId}/checks")
    public CommonResult<List<MonitorUptimeHistory>> checks(
            @PathVariable String projectKey, @PathVariable Long checkId,
            @RequestParam(defaultValue = "50") int limit) {
        return CommonResult.success(uptimeService.history(projectKey, checkId, limit));
    }
}
