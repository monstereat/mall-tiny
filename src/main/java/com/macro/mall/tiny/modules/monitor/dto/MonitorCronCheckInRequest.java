package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorCronCheckInRequest {
    @Size(max = 128)
    private String checkinId;
    @Pattern(regexp = "in_progress|ok|error")
    private String status = "in_progress";
    @Size(max = 64)
    private String environment = "production";
    @Size(max = 512)
    private String message;
}
