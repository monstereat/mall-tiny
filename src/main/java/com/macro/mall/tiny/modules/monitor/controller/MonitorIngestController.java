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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorIngestController {

    private final MonitorIngestService ingestService;
    private final MonitorCronService cronService;

    @PostMapping("/envelope")
    public CommonResult<Map<String, Object>> ingest(
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorEventEnvelope event) {
        attachTraceId(event);
        ingestService.ingest(ingestKey, event);
        return CommonResult.success(Map.of(
                "accepted", true,
                "eventId", event.getEventId()
        ));
    }

    @PostMapping("/envelope/batch")
    public CommonResult<Map<String, Object>> ingestBatch(
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorEventBatchRequest request) {
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
            event.setTraceId(MDC.get("traceId"));
        }
    }
}
