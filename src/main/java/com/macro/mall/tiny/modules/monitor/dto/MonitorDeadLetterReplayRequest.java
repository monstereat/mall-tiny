package com.macro.mall.tiny.modules.monitor.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 指定一条死信记录的位置；每次请求只能重放这一条。 */
@Data
public class MonitorDeadLetterReplayRequest {

    /** 原始监控事件主题名，不允许客户端指定任意 Kafka 主题。 */
    @NotBlank
    private String sourceTopic;

    /** 死信记录所在分区，从 0 开始。 */
    @NotNull
    @Min(0)
    private Integer partition;

    /** 死信记录在该分区里的位置编号，从 0 开始。 */
    @NotNull
    @Min(0)
    private Long offset;
}
