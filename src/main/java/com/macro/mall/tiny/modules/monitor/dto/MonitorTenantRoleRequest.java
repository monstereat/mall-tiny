package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MonitorTenantRoleRequest {
    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9_-]{1,63}")
    private String roleKey;

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull
    @Size(max = 7)
    private List<@NotBlank @Size(max = 64) String> permissions;
}
