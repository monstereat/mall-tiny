package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventBatchRequest;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.service.MonitorIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorIngestController {

    private final MonitorIngestService ingestService;

    @PostMapping("/envelope")
    public CommonResult<Map<String, Object>> ingest(
            @RequestHeader("X-Monitor-Key") String ingestKey,
            @Valid @RequestBody MonitorEventEnvelope event) {
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
        int accepted = ingestService.ingestBatch(ingestKey, request.getEvents());
        return CommonResult.success(Map.of(
                "accepted", accepted
        ));
    }
}
