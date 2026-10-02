package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MonitorAlertSilenceRequest {
    @NotBlank
    private String scope;
    private Long ruleId;
    private String fingerprint;
    private String reason;
    @Min(60)
    @Max(2592000)
    private Integer durationSeconds = 1800;
}
