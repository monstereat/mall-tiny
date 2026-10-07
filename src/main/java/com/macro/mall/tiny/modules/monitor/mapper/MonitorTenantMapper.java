package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.macro.mall.tiny.modules.monitor.model.MonitorTenant;

public interface MonitorTenantMapper extends BaseMapper<MonitorTenant> {
    @Select("SELECT id FROM monitor_tenant WHERE id = #{tenantId} FOR UPDATE")
    Long lockTenant(@Param("tenantId") Long tenantId);
}
