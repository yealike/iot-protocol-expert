package top.yealike.pep.ops.access.domain;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 认证与授权使用的用户身份快照。 */
public record Identity(
        long id, String username, boolean enabled, long authVersion, List<String> roles, List<String> permissions) {

    public List<SimpleGrantedAuthority> authorities() {
        return Stream.concat(roles.stream().map(role -> "ROLE_" + role), permissions.stream())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
