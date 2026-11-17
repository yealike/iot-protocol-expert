package top.yealike.pep.control.access.domain;

import java.time.LocalDateTime;

public class OpsUser {
    public Long id;

    public String username;

    public String passwordHash;

    public boolean enabled = true;
    public LocalDateTime createdAt;
}
