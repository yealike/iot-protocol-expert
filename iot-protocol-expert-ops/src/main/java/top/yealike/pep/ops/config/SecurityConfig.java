package top.yealike.pep.ops.config;

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
import top.yealike.pep.ops.config.properties.OpsProperties;
import top.yealike.pep.ops.config.security.CurrentIdentityFilter;
import top.yealike.pep.ops.config.security.ServiceTokenFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, OpsProperties properties, top.yealike.pep.ops.access.service.IdentityService identities)
            throws Exception {
        http
                // 前端用 fetch 提交登录表单，不带 CSRF token；登录本身是凭据校验，不依赖 token
                .csrf(csrf -> csrf.ignoringRequestMatchers("/internal/**", "/api/**", "/login", "/logout"))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/**", "/api/ops/simulator/**")
                        .permitAll()
                        // 后端只提供 API：登录/登出/健康检查与错误页匿名可取，其余一律要求认证
                        .requestMatchers(
                                "/actuator/**",
                                "/login",
                                "/logout",
                                "/error",
                                "/favicon.ico",
                                "/doc.html",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/webjars/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/knife4j/**")
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
                // 前端独立部署：未认证返回 401，而不是把 fetch 重定向到框架默认登录页
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
}
