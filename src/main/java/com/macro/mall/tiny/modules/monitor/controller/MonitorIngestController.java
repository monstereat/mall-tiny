package com.macro.mall.tiny.modules.monitor.controller;

import com.macro.mall.tiny.common.api.CommonResult;
import com.macro.mall.tiny.modules.monitor.dto.MonitorEventEnvelope;
import com.macro.mall.tiny.modules.monitor.service.MonitorIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MonitorIngestController {

    private final MonitorIngestService ingestService;

    @PostMapping("/envelope")
    public CommonResult<Map<String, Object>> ingest(@Valid @RequestBody MonitorEventEnvelope event) {
        ingestService.ingest(event);
        return CommonResult.success(Map.of(
                "accepted", true,
                "eventId", event.getEventId()
        ));
    }
}
