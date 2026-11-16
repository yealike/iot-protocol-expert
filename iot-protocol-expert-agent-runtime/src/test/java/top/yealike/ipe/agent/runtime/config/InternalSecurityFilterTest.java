package top.yealike.ipe.agent.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.yealike.ipe.agent.runtime.TestFixtures;
import top.yealike.ipe.ai.contract.InternalSignature;

class InternalSecurityFilterTest {

    private static final String PATH = "/internal/agent/v1/releases:prepare";
    private static final byte[] BODY = "{\"releaseId\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsValidSignatureAndRejectsReplay() throws Exception {
        InternalSecurityFilter filter = new InternalSecurityFilter(TestFixtures.properties());
        MockHttpServletRequest request = request(BODY);
        AtomicBoolean called = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> called.set(true));

        assertThat(called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);

        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(request(request.getContentAsByteArray(), usedNonce(request)), replay, (req, res) -> {
            throw new AssertionError("replay must not reach the controller");
        });
        assertThat(replay.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsTamperedBodyAndWrongService() throws Exception {
        InternalSecurityFilter filter = new InternalSecurityFilter(TestFixtures.properties());
        MockHttpServletRequest tampered = request(BODY);
        tampered.setContent("{\"releaseId\":\"y\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(tampered, response, (req, res) -> {
            throw new AssertionError("tampered request must not reach the controller");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_SIGNATURE_INVALID");
    }

    private static MockHttpServletRequest request(byte[] body) {
        return request(body, null);
    }

    private static MockHttpServletRequest request(byte[] body, String nonce) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(body);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String actualNonce = nonce == null ? UUID.randomUUID().toString() : nonce;
        String signature = InternalSignature.sign("test-secret", "POST", PATH, body, timestamp, actualNonce, "runtime");
        request.addHeader(InternalSignature.HEADER_SERVICE, "control");
        request.addHeader(InternalSignature.HEADER_KEY_ID, "control");
        request.addHeader(InternalSignature.HEADER_TIMESTAMP, timestamp);
        request.addHeader(InternalSignature.HEADER_NONCE, actualNonce);
        request.addHeader(InternalSignature.HEADER_SIGNATURE, signature);
        return request;
    }

    private static String usedNonce(MockHttpServletRequest request) {
        return request.getHeader(InternalSignature.HEADER_NONCE);
    }
}
