package top.yealike.pep.control.access;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.pep.control.audit.AuditLog;
import top.yealike.pep.control.access.domain.OpsUser;
import top.yealike.pep.control.audit.mapper.AuditLogMapper;
import top.yealike.pep.control.access.mapper.IdentityMapper;

@Service
public class UserAdministrationService {
    private static final Set<String> ROLES = Set.of("ADMIN", "OPS", "VIEWER");
    private static final Set<String> PERMISSIONS =
            Set.of("iam:user:manage", "ops:read", "ops:operate", "ops:manage", "ai:use", "ai:admin");

    public record Role(String code, String name, List<String> permissions) {}

    private final IdentityMapper iam;
    private final PasswordEncoder passwords;
    private final IdentityService identities;
    private final AuditLogMapper audits;

    public record Change(
            String username,
            String password,
            boolean enabled,
            List<String> roles,
            boolean allStations,
            List<Long> stationIds) {}

    public UserAdministrationService(
            IdentityMapper iam, PasswordEncoder passwords, IdentityService identities, AuditLogMapper audits) {
        this.iam = iam;
        this.passwords = passwords;
        this.identities = identities;
        this.audits = audits;
    }

    public List<IdentityService.Identity> list() {
        return iam.selectAllUserIds().stream()
                .map(id -> identities.byId(id).orElseThrow())
                .toList();
    }

    public List<Role> roles() {
        return iam.selectRoles().stream()
                .map(role -> new Role(role.code(), role.name(), iam.selectRolePermissions(role.code())))
                .toList();
    }

    @Transactional
    public Role updateRole(String code, List<String> permissions, String actor) {
        if (!ROLES.contains(code) || permissions == null || !PERMISSIONS.containsAll(permissions)) throw bad("角色或权限无效");
        if (code.equals("ADMIN") && !permissions.contains("iam:user:manage")) throw bad("管理员角色必须保留用户管理权限");
        if ((permissions.contains("ops:manage") || permissions.contains("ops:operate"))
                && !permissions.contains("ops:read")) throw bad("运营操作和管理权限需要运营读取权限");
        if (permissions.contains("ai:admin") && !permissions.contains("ai:use")) throw bad("AI 管理权限需要 AI 使用权限");
        iam.deleteRolePermissions(code);
        for (String permission : Set.copyOf(permissions)) iam.insertRolePermission(code, permission);
        iam.incrementAuthVersionByRole(code);
        audit(actor, "ROLE_UPDATE", "ROLE", code);
        return roles().stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public IdentityService.Identity create(Change change, String actor) {
        validate(change, true);
        if (iam.countUsername(change.username()) > 0) throw bad("用户名已存在");
        OpsUser user = new OpsUser();
        user.username = change.username();
        user.passwordHash = passwords.encode(change.password());
        user.enabled = change.enabled();
        user.createdAt = LocalDateTime.now();
        iam.insertUser(user, change.allStations());
        long id = user.id;
        assign(id, change);
        audit(actor, "USER_CREATE", "USER", String.valueOf(id));
        return identities.byId(id).orElseThrow();
    }

    @Transactional
    public IdentityService.Identity update(long id, Change change, String actor) {
        validate(change, false);
        var target = identities.byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!target.username().equals(change.username())) throw bad("用户名不能修改");
        if (target.username().equals(actor)
                && (!change.enabled() || !change.roles().contains("ADMIN") || !change.allStations()))
            throw bad("不能停用或移除自己的管理员权限");
        iam.updateUserAccess(id, change.enabled(), change.allStations());
        if (change.password() != null && !change.password().isBlank())
            iam.updatePassword(id, passwords.encode(change.password()));
        iam.deleteUserRoles(id);
        iam.deleteUserStations(id);
        assign(id, change);
        audit(actor, "USER_UPDATE", "USER", String.valueOf(id));
        return identities.byId(id).orElseThrow();
    }

    private void assign(long id, Change change) {
        for (String role : Set.copyOf(change.roles())) iam.insertUserRole(id, role);
        if (!change.allStations())
            for (Long station : Set.copyOf(change.stationIds())) iam.insertUserStation(id, station);
    }

    private void validate(Change c, boolean creating) {
        if (c == null || c.username() == null || !c.username().matches("[a-zA-Z0-9_.-]{3,64}"))
            throw bad("用户名须为 3 至 64 位字母、数字、点、下划线或短横线");
        if ((creating || (c.password() != null && !c.password().isBlank()))
                && (c.password() == null
                        || c.password().length() < 8
                        || c.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72))
            throw bad("密码长度须为 8 至 72 位");
        if (c.roles() == null || c.roles().isEmpty() || !ROLES.containsAll(c.roles())) throw bad("角色无效");
        if (c.stationIds() == null || c.stationIds().stream().anyMatch(id -> id == null || id <= 0))
            throw bad("站点范围无效");
        for (Long id : c.stationIds()) if (!iam.stationExists(id)) throw bad("站点不存在: " + id);
    }

    private void audit(String actor, String action, String targetType, String targetId) {
        AuditLog log = new AuditLog();
        log.actor = actor;
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.result = "SUCCESS";
        log.createdAt = LocalDateTime.now();
        audits.save(log);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
