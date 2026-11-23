package top.yealike.pep.ops.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.pep.ops.config.properties.OpsProperties;

/** 内部调用与模拟器只读接口使用服务令牌，不建立用户会话。 */
public final class ServiceTokenFilter extends OncePerRequestFilter {

    private final OpsProperties properties;

    public ServiceTokenFilter(OpsProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean internal = "/internal".equals(uri) || uri.startsWith("/internal/");
        return !internal && !uri.startsWith("/api/ops/simulator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean internal = "/internal".equals(uri) || uri.startsWith("/internal/");
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
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
