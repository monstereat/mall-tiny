package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_data_deletion_job")
public class MonitorDataDeletionJob implements Serializable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String projectKey;
    private String userId;
    private Date rangeStart;
    private Date rangeEnd;
    private String status;
    private String stage;
    private String previewToken;
    private String previewCountsJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String cursorValue;
    private String deletedCountsJson;
    private Long requestedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private Date createTime;
    private Date updateTime;
    private Date startedAt;
    private Date finishedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Date leaseUntil;
}
