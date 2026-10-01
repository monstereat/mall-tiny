package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_release")
public class MonitorRelease implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String version;
    private String environment;
    private String gitCommit;
    private String branchName;
    private String sourceMapStatus;
    private Date buildTime;
    private Date deployTime;
    private Date createTime;
}
