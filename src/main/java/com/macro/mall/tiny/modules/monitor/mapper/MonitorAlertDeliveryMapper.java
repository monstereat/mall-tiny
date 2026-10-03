package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorAlertDeliveryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MonitorAlertDeliveryMapper extends BaseMapper<MonitorAlertDeliveryEntity> {
    @Delete("DELETE FROM monitor_alert_delivery WHERE created_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") long cutoff);

    @Select("SELECT * FROM monitor_alert_delivery WHERE (status = 'pending' AND next_attempt_at <= #{now}) "
            + "OR (status = 'sending' AND claim_until <= #{now}) "
            + "ORDER BY next_attempt_at ASC LIMIT 100")
    List<MonitorAlertDeliveryEntity> selectRecoverable(@Param("now") long now);

    @Update("UPDATE monitor_alert_delivery SET status = 'sending', claim_until = #{claimUntil}, "
            + "updated_at = #{now} WHERE id = #{id} AND ((status = 'pending' AND next_attempt_at <= #{now}) "
            + "OR (status = 'sending' AND claim_until <= #{now}))")
    int claim(@Param("id") String id, @Param("now") long now, @Param("claimUntil") long claimUntil);
}
