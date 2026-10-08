package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MonitorSamlExchangeRequest {
    @NotBlank
    private String code;
}
