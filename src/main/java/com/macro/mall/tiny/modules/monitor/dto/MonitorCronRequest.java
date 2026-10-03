package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorCronRequest {
    @NotBlank
    @Size(max = 128)
    private String name;
    @NotBlank
    @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")
    private String slug;
    @NotBlank
    @Pattern(regexp = "interval|crontab")
    private String scheduleType;
    @NotBlank
    @Size(max = 128)
    private String schedule;
    @Size(max = 64)
    private String timezone = "UTC";
    @Min(60)
    @Max(2419200)
    private Integer checkinMarginSeconds = 60;
    @Min(60)
    @Max(2419200)
    private Integer maxRuntimeSeconds = 1800;
    @Min(1)
    @Max(100)
    private Integer failureThreshold = 1;
    @Min(1)
    @Max(100)
    private Integer recoveryThreshold = 1;
    @Pattern(regexp = "active|disabled")
    private String status = "active";
}
