package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorCronRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorCron;
import com.macro.mall.tiny.modules.monitor.model.MonitorCronCheckIn;
import com.macro.mall.tiny.modules.monitor.service.MonitorCronService;
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
@RequestMapping("/monitor/admin/{projectKey}/crons")
@RequiredArgsConstructor
public class MonitorCronAdminController {

    private final MonitorCronService cronService;

    @GetMapping
    public CommonResult<List<MonitorCron>> list(@PathVariable String projectKey) {
        return CommonResult.success(cronService.list(projectKey));
    }

    @PostMapping
    public CommonResult<MonitorCron> create(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorCronRequest request) {
        return CommonResult.success(cronService.create(projectKey, request));
    }

    @PutMapping("/{cronId}")
    public CommonResult<MonitorCron> update(
            @PathVariable String projectKey,
            @PathVariable Long cronId,
            @Valid @RequestBody MonitorCronRequest request) {
        return CommonResult.success(cronService.update(projectKey, cronId, request));
    }

    @DeleteMapping("/{cronId}")
    public CommonResult<Void> delete(@PathVariable String projectKey, @PathVariable Long cronId) {
        cronService.delete(projectKey, cronId);
        return CommonResult.success(null);
    }

    @GetMapping("/{cronId}/check-ins")
    public CommonResult<List<MonitorCronCheckIn>> checkIns(
            @PathVariable String projectKey,
            @PathVariable Long cronId,
            @RequestParam(defaultValue = "50") int limit) {
        return CommonResult.success(cronService.checkIns(projectKey, cronId, limit));
    }
}
