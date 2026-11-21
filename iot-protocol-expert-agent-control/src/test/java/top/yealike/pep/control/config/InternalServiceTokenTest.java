package top.yealike.pep.control.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalServiceTokenTest {

    @Test
    void knowledgeRouteRequiresServiceToken() throws Exception {
        var filter = new SecurityConfig.InternalServiceTokenFilter("test-only-service-token");
        var request = new MockHttpServletRequest("POST", "/internal/ai/knowledge/search_protocol_knowledge");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, (req, res) -> fail("未授权请求不应进入知识检索"));
        assertThat(rejected.getStatus()).isEqualTo(401);

        var wrong = new MockHttpServletRequest("POST", "/internal/ai/knowledge/search_protocol_knowledge");
        wrong.addHeader("X-Ops-Service-Token", "wrong-token");
        var wrongResponse = new MockHttpServletResponse();
        filter.doFilter(wrong, wrongResponse, (req, res) -> fail("错误令牌不应进入知识检索"));
        assertThat(wrongResponse.getStatus()).isEqualTo(401);

        var basePath = new MockHttpServletRequest("POST", "/internal/ai/knowledge");
        var basePathResponse = new MockHttpServletResponse();
        filter.doFilter(basePath, basePathResponse, (req, res) -> fail("知识入口本身也必须校验令牌"));
        assertThat(basePathResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void knowledgeRouteAcceptsMatchingServiceToken() throws Exception {
        var filter = new SecurityConfig.InternalServiceTokenFilter("test-only-service-token");
        var request = new MockHttpServletRequest("POST", "/internal/ai/knowledge/search_protocol_knowledge");
        request.addHeader("X-Ops-Service-Token", "test-only-service-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
    }

    @Test
    void otherInternalRoutesKeepExistingBehavior() throws Exception {
        var filter = new SecurityConfig.InternalServiceTokenFilter("test-only-service-token");
        var request = new MockHttpServletRequest("GET", "/internal/ai/v1/runtime/readiness");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
    }
}
