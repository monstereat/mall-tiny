package com.macro.mall.tiny.modules.monitor.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.macro.mall.tiny.modules.monitor.model.MonitorMetricCardinalityDimension;
import com.macro.mall.tiny.modules.monitor.model.MonitorMetricCardinalityDimensionKey;

import java.util.Date;
import java.util.List;

@Mapper
public interface MonitorMetricCardinalityMapper {

    @Insert("INSERT IGNORE INTO monitor_metric_cardinality_project (project_id) VALUES (#{projectId})")
    int ensureProject(@Param("projectId") Long projectId);

    @Select("SELECT metric_count FROM monitor_metric_cardinality_project WHERE project_id = #{projectId} FOR UPDATE")
    Integer lockProject(@Param("projectId") Long projectId);

    @Update("UPDATE monitor_metric_cardinality_project SET metric_count = GREATEST(0, metric_count + #{delta}) WHERE project_id = #{projectId}")
    int adjustProjectMetricCount(@Param("projectId") Long projectId, @Param("delta") int delta);

    @Select("SELECT DISTINCT project_id FROM monitor_metric_cardinality_metric WHERE last_seen < #{cutoff} ORDER BY project_id LIMIT #{limit}")
    List<Long> findProjectsWithExpiredMetrics(@Param("cutoff") Date cutoff, @Param("limit") int limit);

    @Delete("DELETE FROM monitor_metric_cardinality_metric WHERE project_id = #{projectId} AND last_seen < #{cutoff}")
    int deleteExpiredMetricsForProject(@Param("projectId") Long projectId, @Param("cutoff") Date cutoff);

    @Insert("INSERT IGNORE INTO monitor_metric_cardinality_metric (project_id, metric_name) VALUES (#{projectId}, #{metricName})")
    int ensureMetric(@Param("projectId") Long projectId, @Param("metricName") String metricName);

    @Update("UPDATE monitor_metric_cardinality_metric SET last_seen = CURRENT_TIMESTAMP(3) WHERE project_id = #{projectId} AND metric_name = #{metricName}")
    int touchMetric(@Param("projectId") Long projectId, @Param("metricName") String metricName);

    @Select("SELECT dimension_key_count FROM monitor_metric_cardinality_metric WHERE project_id = #{projectId} AND metric_name = #{metricName} FOR UPDATE")
    Integer lockMetric(@Param("projectId") Long projectId, @Param("metricName") String metricName);

    @Update("UPDATE monitor_metric_cardinality_metric SET dimension_key_count = GREATEST(0, dimension_key_count + #{delta}) WHERE project_id = #{projectId} AND metric_name = #{metricName}")
    int adjustDimensionKeyCount(@Param("projectId") Long projectId,
                                @Param("metricName") String metricName,
                                @Param("delta") int delta);

    @Insert("INSERT IGNORE INTO monitor_metric_cardinality_dimension (project_id, metric_name, dimension_key) VALUES (#{projectId}, #{metricName}, #{dimensionKey})")
    int ensureDimension(@Param("projectId") Long projectId,
                        @Param("metricName") String metricName,
                        @Param("dimensionKey") String dimensionKey);

    @Select("SELECT distinct_value_count AS distinctValueCount, last_seen AS lastSeen FROM monitor_metric_cardinality_dimension WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey} FOR UPDATE")
    MonitorMetricCardinalityDimension lockDimension(@Param("projectId") Long projectId,
                                                    @Param("metricName") String metricName,
                                                    @Param("dimensionKey") String dimensionKey);

    @Update("UPDATE monitor_metric_cardinality_dimension SET distinct_value_count = GREATEST(0, distinct_value_count + #{delta}) WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey}")
    int adjustDistinctValueCount(@Param("projectId") Long projectId,
                                 @Param("metricName") String metricName,
                                 @Param("dimensionKey") String dimensionKey,
                                 @Param("delta") int delta);

    @Update("UPDATE monitor_metric_cardinality_dimension SET last_seen = CURRENT_TIMESTAMP(3) WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey}")
    int touchDimension(@Param("projectId") Long projectId,
                       @Param("metricName") String metricName,
                       @Param("dimensionKey") String dimensionKey);

    @Delete("DELETE FROM monitor_metric_cardinality_value WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey} AND last_seen < #{cutoff}")
    int deleteExpiredValues(@Param("projectId") Long projectId,
                            @Param("metricName") String metricName,
                            @Param("dimensionKey") String dimensionKey,
                            @Param("cutoff") Date cutoff);

    @Insert("INSERT IGNORE INTO monitor_metric_cardinality_value (project_id, metric_name, dimension_key, value_hash, last_seen) VALUES (#{projectId}, #{metricName}, #{dimensionKey}, #{valueHash}, CURRENT_TIMESTAMP(3))")
    int insertValueIfAbsent(@Param("projectId") Long projectId,
                            @Param("metricName") String metricName,
                            @Param("dimensionKey") String dimensionKey,
                            @Param("valueHash") byte[] valueHash);

    @Update("UPDATE monitor_metric_cardinality_value SET last_seen = CURRENT_TIMESTAMP(3) WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey} AND value_hash = #{valueHash}")
    int touchValue(@Param("projectId") Long projectId,
                   @Param("metricName") String metricName,
                   @Param("dimensionKey") String dimensionKey,
                   @Param("valueHash") byte[] valueHash);

    @Delete("DELETE FROM monitor_metric_cardinality_dimension WHERE project_id = #{projectId} AND metric_name = #{metricName} AND last_seen < #{cutoff}")
    int deleteExpiredDimensionsForMetric(@Param("projectId") Long projectId,
                                         @Param("metricName") String metricName,
                                         @Param("cutoff") Date cutoff);

    @Select("SELECT project_id AS projectId, metric_name AS metricName, dimension_key AS dimensionKey FROM monitor_metric_cardinality_dimension WHERE last_seen < #{cutoff} ORDER BY project_id, metric_name, dimension_key LIMIT #{limit}")
    List<MonitorMetricCardinalityDimensionKey> findExpiredDimensions(@Param("cutoff") Date cutoff,
                                                                     @Param("limit") int limit);

    @Delete("DELETE FROM monitor_metric_cardinality_dimension WHERE project_id = #{projectId} AND metric_name = #{metricName} AND dimension_key = #{dimensionKey}")
    int deleteDimension(@Param("projectId") Long projectId,
                        @Param("metricName") String metricName,
                        @Param("dimensionKey") String dimensionKey);
}
