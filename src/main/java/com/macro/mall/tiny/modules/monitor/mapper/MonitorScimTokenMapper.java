package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorScimToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MonitorScimTokenMapper extends BaseMapper<MonitorScimToken> {

    @Update("UPDATE monitor_scim_token SET last_used_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int touchLastUsed(@Param("id") Long id);
}
