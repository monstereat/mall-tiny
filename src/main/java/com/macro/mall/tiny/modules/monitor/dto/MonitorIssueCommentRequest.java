package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MonitorIssueCommentRequest {

    @NotBlank
    @Size(max = 2000)
    private String comment;
}
