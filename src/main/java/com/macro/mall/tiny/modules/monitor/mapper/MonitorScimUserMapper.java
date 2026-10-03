package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorScimUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonitorScimUserMapper extends BaseMapper<MonitorScimUser> {

    @Select("SELECT scim_id AS scimId, tenant_id AS tenantId, admin_id AS adminId, external_id AS externalId, " +
            "active, create_time AS createTime, update_time AS updateTime FROM monitor_scim_user " +
            "WHERE tenant_id = #{tenantId} AND external_id = #{externalId} LIMIT 1")
    MonitorScimUser findByExternalId(@Param("tenantId") Long tenantId, @Param("externalId") String externalId);
}
