package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** SDK 批量上报时的 Java 请求对象；Spring MVC 读取 JSON 后会检查这里声明的批次和字段规则。 */
@Data
public class MonitorEventBatchRequest {

    /** 批次不能为空且最多 100 条；{@code @Valid} 还会逐条检查列表里的事件对象。 */
    @NotEmpty
    @Size(max = 100)
    private List<@Valid MonitorEventEnvelope> events = new ArrayList<>();
}
