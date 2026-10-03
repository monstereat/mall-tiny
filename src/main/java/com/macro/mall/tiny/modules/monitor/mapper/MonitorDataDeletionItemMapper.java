package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface MonitorDataDeletionItemMapper extends BaseMapper<MonitorDataDeletionItem> {

    @Delete("DELETE FROM monitor_data_deletion_item WHERE job_id = #{jobId}")
    int deleteByJobId(@Param("jobId") Long jobId);
}
