package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_cron_checkin")
public class MonitorCronCheckIn implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long cronId;
    private String checkinId;
    private String status;
    private String environment;
    private Date startedAt;
    private Date completedAt;
    private Long durationMs;
    private String message;
    private Date createTime;
}
