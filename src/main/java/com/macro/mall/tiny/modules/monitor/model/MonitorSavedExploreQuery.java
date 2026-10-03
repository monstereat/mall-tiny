package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_saved_explore_query")
public class MonitorSavedExploreQuery implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String name;
    private String criteriaJson;
    private Long createdBy;
    private Date createTime;
    private Date updateTime;
}
