package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorProject;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MonitorProjectMapper extends BaseMapper<MonitorProject> {

    @Select("SELECT id FROM monitor_project WHERE id = #{projectId} FOR UPDATE")
    Long lockProject(@Param("projectId") Long projectId);
}
