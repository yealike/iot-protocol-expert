package top.yealike.ipe.ai.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.ipe.ai.support.AiExceptions.AiException;

/** 每个 /api/ai/** 请求都重新校验身份；不接受前端传入的角色、站点范围或内部用户头。 */
public class IdentityFilter extends OncePerRequestFilter {

    private final IdentityService identities;

    public IdentityFilter(IdentityService identities) {
        this.identities = identities;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ai/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            UserIdentity identity = identities.resolve(request.getHeader("Cookie"));
            List<SimpleGrantedAuthority> authorities = java.util.stream.Stream.concat(
                            identity.roles().stream().map(role -> "ROLE_" + role), identity.permissions().stream())
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, authorities));
            chain.doFilter(request, response);
        } catch (AiException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + exception.getMessage() + "\"}");
        }
    }
}
