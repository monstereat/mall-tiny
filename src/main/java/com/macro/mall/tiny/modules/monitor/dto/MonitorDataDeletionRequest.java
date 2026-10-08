package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;

@Data
public class MonitorDataDeletionRequest {
    @NotNull
    private Instant from;
    @NotNull
    private Instant to;
    private String userId;
}
