package com.macro.mall.tiny.modules.monitor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MonitorScimTokenCreated {
    private MonitorScimTokenView token;
    private String value;
    private String baseUrl;
}
