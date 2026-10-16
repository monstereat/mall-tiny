package com.macro.mall.tiny.modules.monitor.dto;

import com.macro.mall.tiny.modules.monitor.domain.MonitorEventType;

/** 死信查看或重放结果；payload 仅对该项目 Owner 返回，便于排查原始事件内容。 */
public record MonitorDeadLetterRecordView(
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
        String failureMessage,
        String payload,
        boolean replayed,
        Integer replayTargetPartition,
        Long replayTargetOffset
) {
}
