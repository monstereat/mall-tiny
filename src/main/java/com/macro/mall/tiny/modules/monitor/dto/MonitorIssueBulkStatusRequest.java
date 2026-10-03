package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MonitorIssueBulkStatusRequest {

    @NotEmpty
    @Size(max = 100)
    private List<@NotNull Long> issueIds;

    @NotNull
    private String status;
}
