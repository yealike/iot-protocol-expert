package top.yealike.pep.modelgateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.pep.model.contract.ModelGatewayCredentials;
import top.yealike.pep.model.contract.RunTokenCodec;
import top.yealike.pep.model.contract.RunTokenPayload;
import top.yealike.pep.modelgateway.config.properties.GatewayProperties;

/** 网关只接受两类调用：Runtime 的短期运行凭据，以及控制服务的内部服务令牌。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GatewayAuthFilter extends OncePerRequestFilter {

    public static final String RUN_TOKEN_ATTRIBUTE = "pep.runToken";
    public static final String SERVICE_CALL_ATTRIBUTE = "pep.serviceCall";
    public static final String SERVICE_TOKEN_HEADER = "X-PEP-Service-Token";

    private final GatewayProperties properties;

    public GatewayAuthFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/")
                || uri.startsWith("/doc.html")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/knife4j/")
                || uri.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/v1/")) {
            authenticateRun(request, response, chain);
            return;
        }
        if (uri.startsWith("/api/model-gateway/")) {
            authenticateService(request, response, chain);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void authenticateRun(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "缺少模型调用凭据");
            return;
        }
        try {
            RunTokenPayload payload = RunTokenCodec.verify(
                    header.substring("Bearer ".length()).strip(),
                    properties.runTokenSecret(),
                    System.currentTimeMillis() / 1000);
            if (!ModelGatewayCredentials.REF.equals(payload.aud())) {
                reject(response, "运行凭据受众不匹配");
                return;
            }
            request.setAttribute(RUN_TOKEN_ATTRIBUTE, payload);
            chain.doFilter(request, response);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            reject(response, exception.getMessage());
        }
    }

    private void authenticateService(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String expected = properties.serviceToken();
        String provided = request.getHeader(SERVICE_TOKEN_HEADER);
        if (expected == null || expected.isBlank() || !constantTimeEquals(expected, provided)) {
            reject(response, "服务身份校验失败");
            return;
        }
        request.setAttribute(SERVICE_CALL_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"error\":\"MODEL_GATEWAY_UNAUTHORIZED\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
