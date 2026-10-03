package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AlertRuleRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String metric;
    private String operator = ">";
    @NotNull
    private BigDecimal thresholdValue;
    private Integer windowSeconds = 300;
    private Integer durationSeconds = 0;
    private Integer cooldownSeconds = 900;
    private String level = "warning";
    private String webhookUrl;
    private Long notificationRouteId;
    private Integer enabled = 1;
}
