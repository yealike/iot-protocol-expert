package top.yealike.pep.control.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
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
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            OpsIdentityClient identities,
            @Value("${ipe.ai.platform.security.internal-service-token:}") String internalServiceToken)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ASYNC)
                        .permitAll()
                        .requestMatchers(
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
                .addFilterBefore(
                        new InternalServiceTokenFilter(internalServiceToken),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new OpsIdentityFilter(identities), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static final class InternalServiceTokenFilter extends OncePerRequestFilter {
        static final String KNOWLEDGE_PATH = "/internal/ai/knowledge";
        static final String KNOWLEDGE_PATH_PREFIX = "/internal/ai/knowledge/";
        private final String expectedToken;

        InternalServiceTokenFilter(String expectedToken) {
            this.expectedToken = expectedToken;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String uri = request.getRequestURI();
            return !KNOWLEDGE_PATH.equals(uri) && !uri.startsWith(KNOWLEDGE_PATH_PREFIX);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String providedToken = request.getHeader("X-Ops-Service-Token");
            if (expectedToken == null
                    || expectedToken.isBlank()
                    || providedToken == null
                    || !MessageDigest.isEqual(
                            expectedToken.getBytes(StandardCharsets.UTF_8),
                            providedToken.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"SERVICE_TOKEN_INVALID\",\"message\":\"服务身份校验失败\"}");
                return;
            }
            chain.doFilter(request, response);
        }
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
