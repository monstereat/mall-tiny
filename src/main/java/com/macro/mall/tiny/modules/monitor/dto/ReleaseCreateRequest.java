package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReleaseCreateRequest {

    @NotBlank
    private String version;

    private String environment = "production";
    private String gitCommit;
    private String branchName;
    private Long buildTime;
    private Long deployTime;
}
