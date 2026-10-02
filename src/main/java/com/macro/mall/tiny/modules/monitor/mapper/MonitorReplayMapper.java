package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface MonitorReplayMapper extends BaseMapper<MonitorReplay> {

    @Delete("DELETE FROM monitor_replay WHERE create_time <= #{cutoff} ORDER BY id LIMIT #{batchSize}")
    int deleteExpiredBatch(@Param("cutoff") Date cutoff, @Param("batchSize") int batchSize);
}
