package top.yealike.ipe.agent.runtime.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.InternalRequestBodies;
import top.yealike.ipe.ai.contract.InternalSignature;

/**
 * Runtime 内部接口 HMAC 校验。
 *
 * <p>时间窗 30 秒 + 本实例进程内 nonce TTL 缓存防重放；请求体在过滤器内缓存，签名与后续反序列化看到完全相同的字节。
 *
 * <p>Runtime 不引入数据库，nonce 不跨实例共享：同一签名请求在时间窗内仍可能被重放到另一个 Runtime 副本。
 * 需要跨副本重放防护时，必须将 Runtime 固定路由到单副本，或由共享存储统一 claim nonce。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalSecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalSecurityFilter.class);
    private static final long WINDOW_SECONDS = 30;
    private static final long NONCE_TTL_SECONDS = 120;

    private final RuntimeProperties properties;
    private final String secret;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanup = new AtomicLong();

    public InternalSecurityFilter(RuntimeProperties properties) {
        this.properties = properties;
        this.secret = properties.resolvedServiceKey();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (secret.isBlank()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "内部服务密钥未配置");
            return;
        }
        String service = request.getHeader(InternalSignature.HEADER_SERVICE);
        String timestamp = request.getHeader(InternalSignature.HEADER_TIMESTAMP);
        String nonce = request.getHeader(InternalSignature.HEADER_NONCE);
        String signature = request.getHeader(InternalSignature.HEADER_SIGNATURE);
        if (!properties.controlServiceName().equals(service) || blank(timestamp) || blank(nonce) || blank(signature)) {
            reject(response, HttpStatus.UNAUTHORIZED, "缺少服务身份");
            return;
        }
        if (service.length() > 64 || nonce.length() > 128 || signature.length() > 128) {
            reject(response, HttpStatus.UNAUTHORIZED, "服务身份头过长");
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            reject(response, HttpStatus.UNAUTHORIZED, "服务身份时间戳无效");
            return;
        }
        long now = Instant.now().getEpochSecond();
        if (seconds < now - WINDOW_SECONDS || seconds > now + WINDOW_SECONDS) {
            reject(response, HttpStatus.UNAUTHORIZED, "服务身份已过期");
            return;
        }
        byte[] body;
        try {
            body = InternalRequestBodies.read(request.getInputStream(), request.getContentLengthLong());
        } catch (InternalRequestBodies.TooLargeException exception) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "内部请求体过大");
            return;
        }
        String pathAndQuery =
                request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        boolean valid = InternalSignature.verify(
                secret, signature, request.getMethod(), pathAndQuery, body, timestamp, nonce, "runtime");
        if (!valid) {
            log.warn("internal signature rejected: service={} path={}", service, request.getRequestURI());
            reject(response, HttpStatus.UNAUTHORIZED, "服务身份签名无效");
            return;
        }
        if (!acceptNonce(service, nonce)) {
            reject(response, HttpStatus.UNAUTHORIZED, "服务身份 nonce 已使用");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean acceptNonce(String service, String nonce) {
        long now = Instant.now().getEpochSecond();
        if (now - lastCleanup.get() > 60) {
            lastCleanup.set(now);
            nonces.entrySet().removeIf(entry -> entry.getValue() < now);
        }
        return nonces.putIfAbsent(service + "\u0000" + nonce, now + NONCE_TTL_SECONDS) == null;
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"error\":\"" + ErrorCodes.INTERNAL_SIGNATURE_INVALID + "\",\"message\":\"" + message + "\"}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 缓存请求体：签名覆盖原始字节，Controller 仍可正常读取。 */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // 同步读取，无需异步监听
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
