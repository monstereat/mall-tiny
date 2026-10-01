package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private Date createTime;
    private Date updateTime;
}
