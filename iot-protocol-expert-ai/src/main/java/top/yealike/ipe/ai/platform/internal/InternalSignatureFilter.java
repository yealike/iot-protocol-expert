package top.yealike.ipe.ai.platform.internal;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import top.yealike.ipe.ai.contract.ErrorCodes;
import top.yealike.ipe.ai.contract.InternalPaths;
import top.yealike.ipe.ai.contract.InternalRequestBodies;
import top.yealike.ipe.ai.contract.InternalSignature;
import top.yealike.ipe.ai.mapper.InternalControlMapper;
import top.yealike.ipe.ai.platform.config.PlatformProperties;

/** Control 内部接口 HMAC 校验；时间窗、服务身份与持久化 nonce 共同阻止重放。 */
@Component
public class InternalSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalSignatureFilter.class);
    private static final long NONCE_TTL_SECONDS = 120;

    private final PlatformProperties properties;
    private final InternalControlMapper control;

    public InternalSignatureFilter(PlatformProperties properties, InternalControlMapper control) {
        this.properties = properties;
        this.control = control;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals(InternalPaths.CONTROL_BASE) || uri.startsWith(InternalPaths.CONTROL_BASE + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PlatformProperties.Security security = properties.security();
        String secret = security.internalServiceKey();
        if (secret == null || secret.isBlank()) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "内部服务密钥未配置");
            return;
        }
        String service = request.getHeader(InternalSignature.HEADER_SERVICE);
        String timestamp = request.getHeader(InternalSignature.HEADER_TIMESTAMP);
        String nonce = request.getHeader(InternalSignature.HEADER_NONCE);
        String signature = request.getHeader(InternalSignature.HEADER_SIGNATURE);
        if (!security.runtimeServiceName().equals(service) || blank(timestamp) || blank(nonce) || blank(signature)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少服务身份");
            return;
        }
        if (service.length() > 64 || nonce.length() > 128 || signature.length() > 128) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "服务身份长度非法");
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "服务身份时间戳无效");
            return;
        }
        long now = Instant.now().getEpochSecond();
        long skew = Math.max(1, security.clockSkewSeconds());
        if (seconds < now - skew || seconds > now + skew) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "服务身份已过期");
            return;
        }
        byte[] body;
        try {
            body = InternalRequestBodies.read(request.getInputStream(), request.getContentLengthLong());
        } catch (InternalRequestBodies.TooLargeException exception) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "内部请求体过大");
            return;
        }
        String pathAndQuery =
                request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        boolean valid = InternalSignature.verify(
                secret, signature, request.getMethod(), pathAndQuery, body, timestamp, nonce, "control");
        if (!valid) {
            log.warn("internal signature rejected: service={} path={}", service, request.getRequestURI());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "服务身份签名无效");
            return;
        }
        if (!acceptNonce(service, nonce)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "服务身份 nonce 已使用");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private boolean acceptNonce(String service, String nonce) {
        return control.insertNonce(service, nonce, NONCE_TTL_SECONDS) > 0;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpiredNonces() {
        try {
            control.deleteExpiredNonces();
        } catch (RuntimeException exception) {
            log.warn("internal nonce cleanup failed: {}", exception.getMessage());
        }
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write("{\"error\":\"" + ErrorCodes.INTERNAL_SIGNATURE_INVALID + "\",\"message\":\"" + message + "\"}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

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
