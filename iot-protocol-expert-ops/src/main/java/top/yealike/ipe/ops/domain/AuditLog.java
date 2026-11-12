package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(length = 64)
    public String actor;

    @Column(nullable = false, length = 64)
    public String action;

    @Column(name = "target_type", length = 64)
    public String targetType;

    @Column(name = "target_id", length = 64)
    public String targetId;

    @Column(nullable = false, length = 24)
    public String result = "SUCCESS";

    @Column(length = 1024)
    public String detail;

    public LocalDateTime createdAt;
}
