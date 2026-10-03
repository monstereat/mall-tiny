package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MonitorTeamRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String teamKey;
}
