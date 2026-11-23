package top.yealike.pep.ops.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.yealike.pep.ops.config.properties.OpsProperties;
import top.yealike.pep.ops.config.security.ServiceTokenFilter;

class InternalIdentityTokenTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal",
                "/internal/ai/v1/identity/session",
                "/internal/ai/rest/resolve_device",
                "/internal/protocol-documents"
            })
    void allInternalRoutesRequireServiceToken(String path) throws Exception {
        var properties = new OpsProperties();
        properties.setServiceToken("test-only-service-token");
        var filter = new ServiceTokenFilter(properties);
        var request = new MockHttpServletRequest("POST", path);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, (req, res) -> fail("未授权请求不应进入身份查询"));
        assertThat(rejected.getStatus()).isEqualTo(401);
        var allowed = new MockHttpServletRequest("POST", path);
        allowed.addHeader("X-Ops-Service-Token", "test-only-service-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                allowed, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
    }
}
