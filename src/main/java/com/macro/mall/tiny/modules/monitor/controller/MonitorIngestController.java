package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventBatchRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.dto.MonitorCronCheckInRequest;
import com.macro.mall.tiny.modules.monitor.model.MonitorCronCheckIn;
import com.macro.mall.tiny.modules.monitor.service.MonitorCronService;
import com.macro.mall.tiny.modules.monitor.service.MonitorIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 接收浏览器 SDK 上报数据的 HTTP 入口。
 * {@code @PostMapping} 把指定 URL 路径接到方法上；Spring MVC 会自动读取请求头和 JSON，并填入方法参数。
 * {@code @Valid} 会先检查请求对象上标注的通用规则；不同事件各自的规则由后面的业务服务检查。
 * {@code @RequiredArgsConstructor} 是 Lombok 注解，会替 final 字段生成构造函数，让 Spring 自动提供所需服务。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorIngestController {

    private final MonitorIngestService ingestService;
    private final MonitorCronService cronService;

    /** 接收一条事件；返回 accepted 只表示接入检查和排队已完成，后台保存工作还在继续。 */
    @PostMapping("/envelope")
    public CommonResult<Map<String, Object>> ingest(
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorEventEnvelope event) {
        // 请求过滤器已生成本次请求的 TraceId；SDK 没传自己的链路编号时，用它作关联编号。
        attachTraceId(event);
        // 事件发进 Kafka 队列后接口就会返回；后台消费者之后再写 ClickHouse、更新错误问题汇总。
        ingestService.ingest(ingestKey, event);
        return CommonResult.success(Map.of(
                "accepted", true,
                "eventId", event.getEventId()
        ));
    }

    /** 接收一批事件；批次项目检查、限流和逐条排队由接入业务服务完成。 */
    @PostMapping("/envelope/batch")
    public CommonResult<Map<String, Object>> ingestBatch(
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorEventBatchRequest request) {
        // 先给每条事件补齐链路编号，再交给业务服务检查、脱敏并放入 Kafka 队列。
        request.getEvents().forEach(this::attachTraceId);
        int accepted = ingestService.ingestBatch(ingestKey, request.getEvents());
        return CommonResult.success(Map.of(
                "accepted", accepted
        ));
    }

    @PostMapping("/monitors/{projectKey}/{slug}/check-ins")
    public CommonResult<MonitorCronCheckIn> startCronCheckIn(
            @PathVariable String projectKey,
            @PathVariable String slug,
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorCronCheckInRequest request) {
        return CommonResult.success(cronService.startCheckIn(
                projectKey, ingestKey, slug, request.getCheckinId(), request));
    }

    @PutMapping("/monitors/{projectKey}/{slug}/check-ins/{checkInId}")
    public CommonResult<MonitorCronCheckIn> finishCronCheckIn(
            @PathVariable String projectKey,
            @PathVariable String slug,
            @PathVariable String checkInId,
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorCronCheckInRequest request) {
        return CommonResult.success(cronService.finishCheckIn(
                projectKey, ingestKey, slug, checkInId, request));
    }

    private void attachTraceId(MonitorEventEnvelope event) {
        if (!StringUtils.hasText(event.getTraceId())) {
            // MDC 是日志框架保存“当前请求信息”的线程上下文；SDK 未传 traceId 时才补上这里的默认编号。
            event.setTraceId(MDC.get("traceId"));
        }
    }
}
