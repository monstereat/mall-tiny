package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;

public interface MonitorReplayMapper extends BaseMapper<MonitorReplay> {

    @Delete("DELETE FROM monitor_replay WHERE create_time <= #{cutoff} ORDER BY create_time, id LIMIT #{batchSize}")
    int deleteExpiredBatch(@Param("cutoff") Date cutoff, @Param("batchSize") int batchSize);

    @Select("SELECT * FROM monitor_replay WHERE project_id=#{projectId} AND start_time>=#{from} AND start_time<#{to} " +
            "AND create_time<=#{snapshotAt} AND id>#{cursor} ORDER BY id LIMIT #{limit}")
    java.util.List<MonitorReplay> selectForDeletionBatch(
            @Param("projectId") Long projectId,
            @Param("from") Date from,
            @Param("to") Date to,
            @Param("snapshotAt") Date snapshotAt,
            @Param("cursor") Long cursor,
            @Param("limit") int limit);
}
