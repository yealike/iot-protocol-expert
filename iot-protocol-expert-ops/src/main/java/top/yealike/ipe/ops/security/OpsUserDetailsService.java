package top.yealike.ipe.ops.security;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import top.yealike.ipe.ops.domain.OpsUser;
import top.yealike.ipe.ops.repository.OpsUserRepository;

@Service
public class OpsUserDetailsService implements UserDetailsService {

    private final OpsUserRepository users;

    public OpsUserDetailsService(OpsUserRepository users) {
        this.users = users;
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
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role)));
    }
}
