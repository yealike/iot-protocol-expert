package top.yealike.pep.ops.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.pep.ops.access.domain.Identity;
import top.yealike.pep.ops.access.service.IdentityService;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 每个请求刷新授权；被停用的账号立即失去会话。 */
public final class CurrentIdentityFilter extends OncePerRequestFilter {
    private final IdentityService identities;

    public CurrentIdentityFilter(IdentityService identities) {
        this.identities = identities;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/ops/users")
                && !"GET".equals(request.getMethod())
                && !"HEAD".equals(request.getMethod())
                && !"1".equals(request.getHeader("X-OPS-Client"))) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        var context = SecurityContextHolder.getContext();
        var auth = context.getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            var identity = identities.byUsername(auth.getName()).filter(Identity::enabled);
            var currentSession = request.getSession(false);
            Object version = currentSession == null ? null : currentSession.getAttribute("iam.authVersion");
            if (identity.isEmpty() || !Objects.equals(version, identity.get().authVersion())) {
                context.setAuthentication(null);
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.sendError(HttpStatus.UNAUTHORIZED.value());
                return;
            }
            var current = identity.get();
            var principal = new UserIdentity(
                    String.valueOf(current.id()),
                    current.username(),
                    current.roles(),
                    current.permissions(),
                    current.authVersion());
            var updated = new UsernamePasswordAuthenticationToken(principal, null, current.authorities());
            updated.setDetails(auth.getDetails());
            context.setAuthentication(updated);
        }
        chain.doFilter(request, response);
    }
}
