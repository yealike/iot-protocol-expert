package top.yealike.ipe.ops.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {}
