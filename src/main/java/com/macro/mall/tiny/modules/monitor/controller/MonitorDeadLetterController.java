package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterRecordView;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterReplayRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorDeadLetterSummaryView;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import com.macro.mall.tiny.modules.monitor.service.MonitorAdminService;
import com.macro.mall.tiny.modules.monitor.service.MonitorDeadLetterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 供项目 Owner 排查和人工重放 Kafka 死信；每次只操作一个明确指定的消息位置。 */
@RestController
@RequestMapping("/monitor/admin/{projectKey}/kafka/dlq")
@RequiredArgsConstructor
public class MonitorDeadLetterController {

    private final MonitorAdminService adminService;
    private final MonitorDeadLetterService deadLetterService;

    /** 查询某个项目、某个来源主题最近的死信摘要，默认最多返回 100 条。 */
    @GetMapping("/{sourceTopic}/records")
    public CommonResult<List<MonitorDeadLetterSummaryView>> list(
            @PathVariable String projectKey,
            @PathVariable String sourceTopic,
            @RequestParam(defaultValue = "100") int limit) {
        MonitorProject project = adminService.requireProjectOwner(projectKey);
        return CommonResult.success(deadLetterService.list(project, sourceTopic, limit));
    }

    /**
     * 查看一条死信的原始事件和失败原因。
     * 先校验项目 Owner，再由 Service 确认死信消息确实属于这个项目。
     */
    @GetMapping("/{sourceTopic}/partitions/{partition}/offsets/{offset}")
    public CommonResult<MonitorDeadLetterRecordView> inspect(
            @PathVariable String projectKey,
            @PathVariable String sourceTopic,
            @PathVariable int partition,
            @PathVariable long offset) {
        MonitorProject project = adminService.requireProjectOwner(projectKey);
        return CommonResult.success(deadLetterService.inspect(project, sourceTopic, partition, offset));
    }

    /**
     * 把指定死信重新发回它原本的 Kafka 主题。
     * 这是人工操作；不会自动反复重放，也不会删除或修改死信主题里的原记录。
     */
    @PostMapping("/replay")
    public CommonResult<MonitorDeadLetterRecordView> replay(
            @PathVariable String projectKey,
            @Valid @RequestBody MonitorDeadLetterReplayRequest request) {
        MonitorProject project = adminService.requireProjectOwner(projectKey);
        return CommonResult.success(deadLetterService.replay(project, request));
    }
}
