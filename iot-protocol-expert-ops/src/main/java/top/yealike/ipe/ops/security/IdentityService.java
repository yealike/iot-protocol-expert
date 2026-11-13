package top.yealike.ipe.ops.security;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

/** Authoritative identity and authorization data shared by OPS and AI. */
@Service
public class IdentityService {
    private final JdbcTemplate jdbc;

    public IdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Identity(
            long id,
            String username,
            boolean enabled,
            boolean allStations,
            long authVersion,
            List<String> roles,
            List<String> permissions,
            List<Long> stationIds) {
        public List<SimpleGrantedAuthority> authorities() {
            return java.util.stream.Stream.concat(roles.stream().map(r -> "ROLE_" + r), permissions.stream())
                    .map(SimpleGrantedAuthority::new)
                    .toList();
        }

        public boolean canAccessStation(Long stationId) {
            return allStations || (stationId != null && stationIds.contains(stationId));
        }
    }

    public java.util.Optional<Identity> byUsername(String username) {
        return jdbc
                .query(
                        "select id, username, enabled, all_stations, auth_version from ops_user where username = ?",
                        (rs, row) -> read(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getBoolean("enabled"),
                                rs.getBoolean("all_stations"),
                                rs.getLong("auth_version")),
                        username)
                .stream()
                .findFirst();
    }

    public java.util.Optional<Identity> byId(long id) {
        return jdbc
                .query(
                        "select id, username, enabled, all_stations, auth_version from ops_user where id = ?",
                        (rs, row) -> read(
                                rs.getLong("id"),
                                rs.getString("username"),
                                rs.getBoolean("enabled"),
                                rs.getBoolean("all_stations"),
                                rs.getLong("auth_version")),
                        id)
                .stream()
                .findFirst();
    }

    private Identity read(long id, String username, boolean enabled, boolean allStations, long authVersion) {
        List<String> roles = jdbc.queryForList(
                "select role_code from iam_user_role where user_id = ? order by role_code", String.class, id);
        List<String> permissions = jdbc.queryForList(
                "select distinct p.permission from iam_role_permission p join iam_user_role ur on ur.role_code = p.role_code where ur.user_id = ? order by p.permission",
                String.class,
                id);
        List<Long> stations = jdbc.queryForList(
                "select station_id from iam_user_station where user_id = ? order by station_id", Long.class, id);
        return new Identity(id, username, enabled, allStations, authVersion, roles, permissions, stations);
    }
}
