package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ops_user")
public class OpsUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64)
    public String username;

    @Column(name = "password_hash", nullable = false, length = 128)
    public String passwordHash;

    @Column(nullable = false, length = 24)
    public String role;

    public boolean enabled = true;
    public LocalDateTime createdAt;
}
