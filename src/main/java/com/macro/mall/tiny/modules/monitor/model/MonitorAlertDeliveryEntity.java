package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("monitor_alert_delivery")
public class MonitorAlertDeliveryEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private Long projectId;
    private Long ruleId;
    private Long alertRecordId;
    private String alertStatus;
    private String status;
    private Integer attempts;
    private Long nextAttemptAt;
    private Long claimUntil;
    private Long createdAt;
    private Long updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
}
