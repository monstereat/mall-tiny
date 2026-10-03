package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDataDeletionRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import com.macro.mall.tiny.modules.monitor.service.MonitorDataDeletionService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@RestController
@RequestMapping("/monitor/admin/{projectKey}/data-deletion")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "monitor.data-deletion.api-enabled", havingValue = "true")
public class MonitorDataDeletionController {
    private final MonitorDataDeletionService deletionService;

    @GetMapping
    public CommonResult<List<MonitorDataDeletionJob>> recentJobs(@PathVariable String projectKey) {
        return CommonResult.success(deletionService.recentJobs(projectKey));
    }

    @PostMapping("/preview")
    public CommonResult<MonitorDataDeletionJob> preview(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorDataDeletionRequest request) {
        return CommonResult.success(deletionService.preview(projectKey, request));
    }

    @PostMapping("/{jobId}/execute")
    public CommonResult<MonitorDataDeletionJob> execute(
            @PathVariable String projectKey,
            @PathVariable Long jobId,
            @Valid @RequestBody ExecuteRequest request) {
        return CommonResult.success(deletionService.execute(projectKey, jobId, request.getPreviewToken()));
    }

    @GetMapping("/{jobId}")
    public CommonResult<MonitorDataDeletionJob> status(
            @PathVariable String projectKey,
            @PathVariable Long jobId) {
        return CommonResult.success(deletionService.status(projectKey, jobId));
    }

    @PostMapping("/{jobId}/retry")
    public CommonResult<MonitorDataDeletionJob> retry(
            @PathVariable String projectKey,
            @PathVariable Long jobId) {
        return CommonResult.success(deletionService.retry(projectKey, jobId));
    }

    @Data
    public static class ExecuteRequest {
        private String previewToken;
    }
}
