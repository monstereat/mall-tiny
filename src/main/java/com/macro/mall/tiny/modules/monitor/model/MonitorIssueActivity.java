package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_issue_activity")
public class MonitorIssueActivity implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long issueId;
    private Long actorAdminId;
    private String actorName;
    private String activityType;
    private String commentText;
    private String previousStatus;
    private String newStatus;
    private Date createTime;
}
