package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MonitorEventBatchRequest {

    @NotEmpty
    @Size(max = 100)
    private List<@Valid MonitorEventEnvelope> events = new ArrayList<>();
}
