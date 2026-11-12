package top.yealike.ipe.ops.config;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OpsProperties properties) throws Exception {
        http
                // SPA 用 fetch 提交登录表单，不带 CSRF token；登录本身是凭据校验，不依赖 token
                .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/**", "/api/**", "/login", "/logout"))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/**", "/api/ops/simulator/**")
                        .permitAll()
                        // SPA 外壳与其静态资源必须匿名可取，否则未登录时连登录页都加载不出来
                        .requestMatchers(
                                "/actuator/**",
                                "/login",
                                "/logout",
                                "/favicon.ico",
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/overview",
                                "/devices",
                                "/devices/**",
                                "/products",
                                "/products/**",
                                "/alarms",
                                "/campus",
                                "/ops")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                // 园区 3D 沙盘需要被模拟器页面内嵌：只放行本机来源，而不是整体关闭点击劫持防护
                .headers(headers -> headers.frameOptions(frame -> frame.disable())
                        .contentSecurityPolicy(csp ->
                                csp.policyDirectives("frame-ancestors 'self' http://127.0.0.1:* http://localhost:*")))
                .formLogin(form -> form.defaultSuccessUrl("/overview", true))
                .httpBasic(basic -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(new ServiceTokenFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 内部调用与模拟器只读接口使用服务令牌，不建立用户会话。 */
    static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final OpsProperties properties;

        ServiceTokenFilter(OpsProperties properties) {
            this.properties = properties;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String uri = request.getRequestURI();
            return !uri.startsWith("/internal/") && !uri.startsWith("/api/ops/simulator/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String uri = request.getRequestURI();
            boolean internal = uri.startsWith("/internal/");
            String expected = internal ? properties.getServiceToken() : properties.getSimulatorToken();
            String header = internal ? "X-Ops-Service-Token" : "X-Ops-Simulator-Token";
            String provided = request.getHeader(header);
            if (expected == null || expected.isBlank() || !constantTimeEquals(expected, provided)) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"SERVICE_TOKEN_INVALID\",\"message\":\"服务身份校验失败\"}");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean constantTimeEquals(String expected, String provided) {
            if (provided == null || expected.length() != provided.length()) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < expected.length(); i++) {
                result |= expected.charAt(i) ^ provided.charAt(i);
            }
            return result == 0;
        }
    }
}
