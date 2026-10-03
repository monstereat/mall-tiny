package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

public interface MonitorDataDeletionJobMapper extends BaseMapper<MonitorDataDeletionJob> {
    @Update("UPDATE monitor_data_deletion_job SET status='RUNNING', lease_until=DATE_ADD(NOW(3), INTERVAL 10 MINUTE) " +
            "WHERE id=#{id} AND (status='QUEUED' OR (status='RUNNING' AND lease_until < NOW(3)))")
    int claim(@Param("id") Long id);

    @Update("UPDATE monitor_data_deletion_job SET user_id=NULL, cursor_value=NULL, status='COMPLETED', " +
            "stage='COMPLETE', lease_until=NULL, finished_at=#{finishedAt}, update_time=NOW(3) WHERE id=#{id}")
    int completeAudit(@Param("id") Long id, @Param("finishedAt") Date finishedAt);
}
