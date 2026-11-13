package top.yealike.ipe.ops.security;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAdministrationService {
    private static final Set<String> ROLES = Set.of("ADMIN", "OPS", "VIEWER");
    private static final Set<String> PERMISSIONS =
            Set.of("iam:user:manage", "ops:read", "ops:operate", "ops:manage", "ai:use", "ai:admin");

    public record Role(String code, String name, List<String> permissions) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final IdentityService identities;

    public record Change(
            String username,
            String password,
            boolean enabled,
            List<String> roles,
            boolean allStations,
            List<Long> stationIds) {}

    public UserAdministrationService(JdbcTemplate jdbc, PasswordEncoder passwords, IdentityService identities) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.identities = identities;
    }

    public List<IdentityService.Identity> list() {
        return jdbc.queryForList("select id from ops_user order by id", Long.class).stream()
                .map(id -> identities.byId(id).orElseThrow())
                .toList();
    }

    public List<Role> roles() {
        return jdbc.query(
                "select code, name from iam_role order by code",
                (rs, row) -> new Role(
                        rs.getString("code"),
                        rs.getString("name"),
                        jdbc.queryForList(
                                "select permission from iam_role_permission where role_code = ? order by permission",
                                String.class,
                                rs.getString("code"))));
    }

    @Transactional
    public Role updateRole(String code, List<String> permissions, String actor) {
        if (!ROLES.contains(code) || permissions == null || !PERMISSIONS.containsAll(permissions)) throw bad("角色或权限无效");
        if (code.equals("ADMIN") && !permissions.contains("iam:user:manage")) throw bad("管理员角色必须保留用户管理权限");
        if ((permissions.contains("ops:manage") || permissions.contains("ops:operate"))
                && !permissions.contains("ops:read")) throw bad("运营操作和管理权限需要运营读取权限");
        if (permissions.contains("ai:admin") && !permissions.contains("ai:use")) throw bad("AI 管理权限需要 AI 使用权限");
        jdbc.update("delete from iam_role_permission where role_code = ?", code);
        for (String permission : Set.copyOf(permissions))
            jdbc.update("insert into iam_role_permission(role_code,permission) values (?,?)", code, permission);
        jdbc.update(
                "update ops_user set auth_version = auth_version + 1 where id in (select user_id from iam_user_role where role_code = ?)",
                code);
        jdbc.update(
                "insert into audit_log(actor,action,target_type,target_id,result,created_at) values (?, 'ROLE_UPDATE', 'ROLE', ?, 'SUCCESS', now())",
                actor,
                code);
        return roles().stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public IdentityService.Identity create(Change change, String actor) {
        validate(change, true);
        if (!jdbc.queryForList("select id from ops_user where username = ?", Long.class, change.username())
                .isEmpty()) throw bad("用户名已存在");
        Long id = jdbc.queryForObject(
                "insert into ops_user(username,password_hash,enabled,all_stations,created_at) values (?,?,?,?,now()) returning id",
                Long.class,
                change.username(),
                passwords.encode(change.password()),
                change.enabled(),
                change.allStations());
        assign(id, change);
        audit(actor, "USER_CREATE", id);
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
        jdbc.update(
                "update ops_user set enabled = ?, all_stations = ?, auth_version = auth_version + 1 where id = ?",
                change.enabled(),
                change.allStations(),
                id);
        if (change.password() != null && !change.password().isBlank())
            jdbc.update("update ops_user set password_hash = ? where id = ?", passwords.encode(change.password()), id);
        jdbc.update("delete from iam_user_role where user_id = ?", id);
        jdbc.update("delete from iam_user_station where user_id = ?", id);
        assign(id, change);
        audit(actor, "USER_UPDATE", id);
        return identities.byId(id).orElseThrow();
    }

    private void assign(long id, Change change) {
        for (String role : Set.copyOf(change.roles()))
            jdbc.update("insert into iam_user_role(user_id,role_code) values (?,?)", id, role);
        if (!change.allStations())
            for (Long station : Set.copyOf(change.stationIds()))
                jdbc.update("insert into iam_user_station(user_id,station_id) values (?,?)", id, station);
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
        for (Long id : c.stationIds())
            if (jdbc.queryForObject("select count(*) from station where id = ?", Long.class, id) == 0)
                throw bad("站点不存在: " + id);
    }

    private void audit(String actor, String action, long id) {
        jdbc.update(
                "insert into audit_log(actor,action,target_type,target_id,result,created_at) values (?,?, 'USER', ?, 'SUCCESS', now())",
                actor,
                action,
                String.valueOf(id));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
