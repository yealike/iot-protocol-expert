package top.yealike.ipe.ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.ipe.ai.identity.IdentityFilter;
import top.yealike.ipe.ai.identity.IdentityService;

/**
 * AI 服务不提供登录入口：身份一律由 ops 认证服务校验。
 *
 * <p>写请求额外要求自定义头 X-AI-Client，配合不开放 CORS，阻断普通表单型 CSRF。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityService identities) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/**", "/error")
                        .permitAll()
                        .requestMatchers("/api/ai/**")
                        .hasAuthority("ai:use")
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new IdentityFilter(identities), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ClientHeaderFilter(), IdentityFilter.class);
        return http.build();
    }

    /** 只读 GET/SSE 不受影响；写操作必须带自定义头。 */
    static final class ClientHeaderFilter extends OncePerRequestFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String uri = request.getRequestURI();
            if (!uri.startsWith("/api/ai/")) {
                return true;
            }
            String method = request.getMethod();
            return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String marker = request.getHeader("X-AI-Client");
            if (marker == null || marker.isBlank()) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"CLIENT_HEADER_REQUIRED\",\"message\":\"缺少客户端标记，请求被拒绝\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
