package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class MonitorSavedExploreQueryRequest {
    @NotBlank
    @Size(max = 100)
    private String name;
    @Min(1)
    @Max(2160)
    private int hours = 24;
    @Size(max = 16)
    private String type;
    @Size(max = 128)
    private String environment;
    @Size(max = 128)
    private String release;
    @Size(max = 128)
    private String traceId;
    @Size(max = 512)
    private String query;
    @Size(max = 128)
    private String userId;
    @Size(max = 64)
    private String tagKey;
    @Size(max = 128)
    private String tagValue;
    @Size(max = 80)
    private String groupBy = "signal";
    @Size(max = 20)
    private String aggregation = "count";
    @Size(max = 80)
    private String field = "value";
    @Size(max = 128)
    private String formula;
    @Size(max = 5)
    private List<@NotBlank @Size(max = 128) @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z_:][A-Za-z0-9_.:-]{0,127}") String> formulaMetrics;
}
