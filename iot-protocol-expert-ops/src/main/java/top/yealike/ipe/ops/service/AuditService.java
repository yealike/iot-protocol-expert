package top.yealike.ipe.ops.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.domain.AuditLog;
import top.yealike.ipe.ops.repository.AuditLogRepository;

@Service
public class AuditService {

    private final AuditLogRepository audits;

    public AuditService(AuditLogRepository audits) {
        this.audits = audits;
    }

    @Transactional
    public void record(String actor, String action, String targetType, String targetId, String result, String detail) {
        AuditLog log = new AuditLog();
        log.actor = actor;
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.result = result == null ? "SUCCESS" : result;
        log.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        log.createdAt = LocalDateTime.now();
        audits.save(log);
    }

    public List<AuditLog> recent() {
        return audits.findAll(PageRequest.of(0, 200)).getContent();
    }
}
