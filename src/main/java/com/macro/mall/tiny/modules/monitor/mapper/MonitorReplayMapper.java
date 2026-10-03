package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorReplay;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface MonitorReplayMapper extends BaseMapper<MonitorReplay> {

    @Select("SELECT r.id AS id, r.object_key AS objectKey, p.project_key AS projectKey " +
            "FROM monitor_replay r JOIN monitor_project p ON p.id = r.project_id " +
            "WHERE r.create_time <= #{cutoff} ORDER BY r.create_time, r.id LIMIT #{batchSize}")
    List<Map<String, Object>> selectExpiredBatch(@Param("cutoff") Date cutoff, @Param("batchSize") int batchSize);

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
