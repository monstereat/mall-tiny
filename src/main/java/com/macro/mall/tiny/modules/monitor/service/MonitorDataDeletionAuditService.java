package com.macro.mall.tiny.modules.monitor.service;

import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionItemMapper;
import com.macro.mall.tiny.modules.monitor.mapper.MonitorDataDeletionJobMapper;
import com.macro.mall.tiny.modules.monitor.model.MonitorDataDeletionJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class MonitorDataDeletionAuditService {

    private final MonitorDataDeletionItemMapper itemMapper;
    private final MonitorDataDeletionJobMapper jobMapper;

    @Transactional
    public void complete(MonitorDataDeletionJob job) {
        itemMapper.deleteByJobId(job.getId());
        job.setUserId(null);
        job.setCursorValue(null);
        job.setStatus("COMPLETED");
        job.setStage("COMPLETE");
        job.setLeaseUntil(null);
        Date finishedAt = new Date();
        job.setFinishedAt(finishedAt);
        jobMapper.completeAudit(job.getId(), finishedAt);
    }
}
