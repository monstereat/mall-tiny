package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorTenantSamlConfigRequest {
    private boolean enabled;
    @NotBlank
    @Size(max = 200_000)
    private String metadataXml;
    @NotBlank
    @Size(max = 128)
    private String emailAttribute = "email";
}
