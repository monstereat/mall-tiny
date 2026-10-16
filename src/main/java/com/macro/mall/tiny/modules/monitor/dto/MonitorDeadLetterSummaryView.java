package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;

/** 死信列表只返回排查摘要；完整事件内容由单条查看接口按需读取。 */
public record MonitorDeadLetterSummaryView(
        String sourceTopic,
        String deadLetterTopic,
        int deadLetterPartition,
        long deadLetterOffset,
        long deadLetterTimestamp,
        int originalPartition,
        long originalOffset,
        String eventId,
        MonitorEventType eventType,
        String failureClass,
        String failureMessage
) {
}
