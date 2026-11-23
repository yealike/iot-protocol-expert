package top.yealike.pep.ops.audit.domain;

import java.time.LocalDateTime;

public class AuditLog {
    public Long id;

    public String actor;

    public String action;

    public String targetType;

    public String targetId;

    public String result = "SUCCESS";

    public String detail;

    public LocalDateTime createdAt;
}
