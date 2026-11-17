package top.yealike.pep.control.audit;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.audit.AuditLog;
import top.yealike.pep.control.audit.mapper.AuditLogMapper;

@Service
public class AuditService {

    private final AuditLogMapper audits;

    public AuditService(AuditLogMapper audits) {
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
        return audits.findRecent200();
    }
}
