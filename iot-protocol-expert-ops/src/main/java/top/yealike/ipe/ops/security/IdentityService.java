package top.yealike.ipe.ops.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ops.mapper.IdentityMapper;

/** Authoritative identity and authorization data shared by OPS and AI. */
@Service
public class IdentityService {
    private final IdentityMapper identities;

    public IdentityService(IdentityMapper identities) {
        this.identities = identities;
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
        return identities.findIdentityByUsername(username).map(this::read);
    }

    public java.util.Optional<Identity> byId(long id) {
        return identities.findIdentityById(id).map(this::read);
    }

    private Identity read(IdentityMapper.IdentityRow row) {
        long id = row.id();
        List<String> roles = identities.selectRoleCodes(id);
        List<String> permissions = identities.selectPermissions(id);
        List<Long> stations = identities.selectStationIds(id);
        return new Identity(
                id, row.username(), row.enabled(), row.allStations(), row.authVersion(), roles, permissions, stations);
    }
}
