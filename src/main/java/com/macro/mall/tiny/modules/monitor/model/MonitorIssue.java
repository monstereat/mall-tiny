package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_issue")
public class MonitorIssue implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String fingerprint;
    private String title;
    private String status;
    private Long eventCount;
    private Long affectedUsers;
    private Date firstSeen;
    private Date lastSeen;
    private String latestRelease;
    private java.util.Date regressedAt;
    private Date resolvedAt;
    private Date createTime;
    private Date updateTime;

    @TableField(exist = false)
    private boolean newIssue;

    @TableField(exist = false)
    private long eventsLast24h;

    @TableField(exist = false)
    private long eventsPrevious24h;
}
