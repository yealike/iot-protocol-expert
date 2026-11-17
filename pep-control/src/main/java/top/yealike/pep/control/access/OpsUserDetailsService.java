package top.yealike.pep.control.access;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import top.yealike.pep.control.access.domain.OpsUser;
import top.yealike.pep.control.access.mapper.OpsUserMapper;

@Service
public class OpsUserDetailsService implements UserDetailsService {

    private final OpsUserMapper users;
    private final IdentityService identities;

    public OpsUserDetailsService(OpsUserMapper users, IdentityService identities) {
        this.users = users;
        this.identities = identities;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        OpsUser user =
                users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return new User(
                user.username,
                user.passwordHash,
                user.enabled,
                true,
                true,
                true,
                identities.byUsername(username).orElseThrow().authorities());
    }
}
