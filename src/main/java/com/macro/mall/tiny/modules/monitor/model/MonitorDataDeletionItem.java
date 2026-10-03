package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("monitor_data_deletion_item")
public class MonitorDataDeletionItem implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    private String itemType;
    private String idempotencyKey;
    private String itemValue;
}
