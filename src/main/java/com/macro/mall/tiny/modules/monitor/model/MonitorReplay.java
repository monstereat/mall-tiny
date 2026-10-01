package com.macro.mall.tiny.modules.monitor.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("monitor_replay")
public class MonitorReplay implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private String eventId;
    private String sessionId;
    private String releaseVersion;
    private String objectKey;
    private Integer eventCount;
    private Date startTime;
    private Date endTime;
    private Date createTime;
}
