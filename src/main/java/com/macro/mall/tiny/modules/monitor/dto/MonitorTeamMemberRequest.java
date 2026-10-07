package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class MonitorTeamMemberRequest {
    @NotNull
    private Long adminId;

    @NotNull
    @Pattern(regexp = "MEMBER|VIEWER")
    private String role;
}
