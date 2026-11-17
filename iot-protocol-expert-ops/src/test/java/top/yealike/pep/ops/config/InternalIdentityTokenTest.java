package top.yealike.pep.ops.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalIdentityTokenTest {
    @Test
    void internalIdentityRequiresServiceToken() throws Exception {
        var properties = new OpsProperties();
        properties.setServiceToken("test-only-service-token");
        var filter = new SecurityConfig.ServiceTokenFilter(properties);
        var request = new MockHttpServletRequest("POST", "/internal/ai/v1/identity/session");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, (req, res) -> fail("未授权请求不应进入身份查询"));
        assertThat(rejected.getStatus()).isEqualTo(401);
        var allowed = new MockHttpServletRequest("POST", "/internal/ai/v1/identity/session");
        allowed.addHeader("X-Ops-Service-Token", "test-only-service-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                allowed, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
    }
}
