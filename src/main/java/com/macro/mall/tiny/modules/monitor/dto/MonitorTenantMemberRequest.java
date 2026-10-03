package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class MonitorTenantMemberRequest {
    @NotNull
    private Long adminId;
    @NotNull
    @Pattern(regexp = "OWNER|MEMBER|VIEWER")
    private String role;
    private Long customRoleId;
}
