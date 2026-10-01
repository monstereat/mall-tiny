package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class MonitorEventEnvelope {

    @NotBlank
    private String eventId;

    @NotBlank
    private String projectId;

    @NotNull
    private MonitorEventType eventType;

    @NotNull
    private Long timestamp;

    private String sessionId;
    private String userId;
    private String release;
    private String environment = "production";
    private String pageUrl;
    private String sdkVersion;
    private String traceId;
    private Map<String, Object> device = new HashMap<>();

    @NotNull
    private Map<String, Object> data = new HashMap<>();
}
