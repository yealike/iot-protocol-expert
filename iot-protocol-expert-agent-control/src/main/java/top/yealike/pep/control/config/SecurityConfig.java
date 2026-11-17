package top.yealike.pep.control.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.support.AiExceptions.AiException;

/** Control 不创建浏览器会话；每次用户请求都向 OPS 校验原始 Cookie。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OpsIdentityClient identities) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/internal/**",
                                "/actuator/**",
                                "/error",
                                "/doc.html",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/webjars/**",
                                "/knife4j/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(errors ->
                        errors.authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
                .addFilterBefore(new OpsIdentityFilter(identities), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static final class OpsIdentityFilter extends OncePerRequestFilter {
        private final OpsIdentityClient identities;

        OpsIdentityFilter(OpsIdentityClient identities) {
            this.identities = identities;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                var identity = identities.resolveCookie(request.getHeader("Cookie"));
                var authorities = java.util.stream.Stream.concat(
                                identity.roles().stream().map(role -> "ROLE_" + role), identity.permissions().stream())
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, authorities));
            } catch (AiException exception) {
                response.sendError(exception.status(), exception.getMessage());
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
