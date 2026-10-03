package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorScimTokenRequest {
    @NotBlank
    @Size(max = 128)
    private String name;
}
