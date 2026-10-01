package com.macro.mall.tiny.modules.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorIssue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface MonitorIssueMapper extends BaseMapper<MonitorIssue> {

    @Insert("""
        INSERT INTO monitor_issue
            (project_id, fingerprint, title, status, event_count, affected_users, first_seen, last_seen, latest_release)
        VALUES
            (#{projectId}, #{fingerprint}, #{title}, 'unresolved', 1, #{affectedUsers}, #{eventTime}, #{eventTime}, #{release})
        ON DUPLICATE KEY UPDATE
            title = VALUES(title),
            event_count = event_count + 1,
            affected_users = GREATEST(affected_users, VALUES(affected_users)),
            last_seen = GREATEST(last_seen, VALUES(last_seen)),
            latest_release = VALUES(latest_release)
        """)
    int upsert(
            @Param("projectId") Long projectId,
            @Param("fingerprint") String fingerprint,
            @Param("title") String title,
            @Param("affectedUsers") Long affectedUsers,
            @Param("eventTime") Date eventTime,
            @Param("release") String release
    );
}
