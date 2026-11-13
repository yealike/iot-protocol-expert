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
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, OpsProperties properties, top.yealike.ipe.ops.security.IdentityService identities)
            throws Exception {
        http
                // 前端用 fetch 提交登录表单，不带 CSRF token；登录本身是凭据校验，不依赖 token
                .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/**", "/api/**", "/login", "/logout"))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/**", "/api/ops/simulator/**")
                        .permitAll()
                        // 后端只提供 API：登录/登出/健康检查与错误页匿名可取，其余一律要求认证
                        .requestMatchers("/actuator/**", "/login", "/logout", "/error", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/api/ops/auth/**")
                        .authenticated()
                        .requestMatchers("/api/ops/users", "/api/ops/users/**")
                        .hasAuthority("iam:user:manage")
                        .requestMatchers("/api/ops/**")
                        .hasAuthority("ops:read")
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                // 前端是独立站点：未认证返回 401，而不是把 fetch 重定向到框架默认登录页
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
                // 登录成功/失败都以 JSON 响应，前端只关心状态码，不再由后端转发 SPA 路由
                .formLogin(form -> form.successHandler((request, response, authentication) -> {
                            request.getSession()
                                    .setAttribute(
                                            "iam.authVersion",
                                            identities
                                                    .byUsername(authentication.getName())
                                                    .orElseThrow()
                                                    .authVersion());
                            writeJson(response, HttpStatus.OK, "{\"authenticated\":true}");
                        })
                        .failureHandler((request, response, exception) -> writeJson(
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "{\"error\":\"BAD_CREDENTIALS\",\"message\":\"账号或密码不正确\"}")))
                .logout(logout -> logout.logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(new ServiceTokenFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new CurrentIdentityFilter(identities),
                        org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    /** Refresh authorization on every request; disabled users lose their session immediately. */
    static final class CurrentIdentityFilter extends OncePerRequestFilter {
        private final top.yealike.ipe.ops.security.IdentityService identities;

        CurrentIdentityFilter(top.yealike.ipe.ops.security.IdentityService identities) {
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
            var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
            var auth = context.getAuthentication();
            if (auth != null
                    && auth.isAuthenticated()
                    && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                var identity = identities
                        .byUsername(auth.getName())
                        .filter(top.yealike.ipe.ops.security.IdentityService.Identity::enabled);
                var currentSession = request.getSession(false);
                Object version = currentSession == null ? null : currentSession.getAttribute("iam.authVersion");
                if (identity.isEmpty()
                        || !java.util.Objects.equals(version, identity.get().authVersion())) {
                    context.setAuthentication(null);
                    var session = request.getSession(false);
                    if (session != null) session.invalidate();
                    response.sendError(HttpStatus.UNAUTHORIZED.value());
                    return;
                }
                var updated = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        auth.getPrincipal(),
                        auth.getCredentials(),
                        identity.get().authorities());
                updated.setDetails(auth.getDetails());
                context.setAuthentication(updated);
                // OPS queries do not yet apply row-level station filters. Fail closed for restricted users.
                if (!identity.get().allStations()
                        && request.getRequestURI().startsWith("/api/ops/")
                        && !request.getRequestURI().startsWith("/api/ops/auth/")) {
                    response.sendError(HttpStatus.FORBIDDEN.value(), "受限站点账号暂不支持访问运营数据");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
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
