package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorUptimeCheckRequest {

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]*")
    private String slug;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Size(max = 2048)
    private String url;

    @NotBlank
    @Pattern(regexp = "GET|HEAD")
    private String method = "GET";

    @Min(30)
    @Max(86400)
    private Integer intervalSeconds = 60;

    @Min(500)
    @Max(30000)
    private Integer timeoutMs = 5000;

    @Min(100)
    @Max(599)
    private Integer expectedStatusCode = 200;

    @Min(1)
    @Max(100)
    private Integer failureThreshold = 1;

    @Min(1)
    @Max(100)
    private Integer recoveryThreshold = 1;

    @NotBlank
    @Pattern(regexp = "active|disabled")
    private String status = "active";
}
