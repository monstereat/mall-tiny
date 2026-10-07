package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorCronCheckIn;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface MonitorCronCheckInMapper extends BaseMapper<MonitorCronCheckIn> {

    @Delete("DELETE FROM monitor_cron_checkin WHERE completed_at < #{cutoff} " +
            "ORDER BY completed_at ASC, id ASC LIMIT #{limit}")
    int deleteCompletedBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);

    @Delete("DELETE FROM monitor_cron_checkin WHERE status = 'in_progress' AND started_at < #{cutoff} " +
            "ORDER BY started_at ASC, id ASC LIMIT #{limit}")
    int deleteStaleInProgressBefore(@Param("cutoff") Date cutoff, @Param("limit") int limit);
}
