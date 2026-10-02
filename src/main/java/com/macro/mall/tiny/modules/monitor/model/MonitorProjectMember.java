package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_project_member")
public class MonitorProjectMember implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long adminId;
    private String role;
    private Date createTime;
    private Date updateTime;
}
