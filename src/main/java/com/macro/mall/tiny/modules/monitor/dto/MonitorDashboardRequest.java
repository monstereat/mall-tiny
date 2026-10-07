package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MonitorDashboardRequest {
    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 20)
    private List<@NotNull Long> queryIds;
}
