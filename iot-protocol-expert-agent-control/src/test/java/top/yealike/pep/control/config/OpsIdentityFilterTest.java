package top.yealike.pep.control.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import top.yealike.pep.control.integration.OpsIdentityClient;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.runtime.contract.UserIdentity;

class OpsIdentityFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesOriginalCookieAndPreservesAuthoritiesWithoutCreatingSession() throws Exception {
        var identities = mock(OpsIdentityClient.class);
        when(identities.resolveCookie("JSESSIONID=test-session"))
                .thenReturn(new UserIdentity("1", "tester", List.of("ADMIN"), List.of("ai:admin"), 3));
        var request = new MockHttpServletRequest("GET", "/api/ai/v1/agents");
        request.addHeader("Cookie", "JSESSIONID=test-session");
        new SecurityConfig.OpsIdentityFilter(identities)
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    assertThat(auth.getName()).isEqualTo("tester");
                    assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_ADMIN", "ai:admin");
                });
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void refusesExpiredSessionAndPreservesUnavailableUpstreamStatus() throws Exception {
        for (int expected : List.of(401, 502)) {
            var identities = mock(OpsIdentityClient.class);
            when(identities.resolveCookie(null))
                    .thenThrow(expected == 401 ? AiExceptions.unauthorized("会话失效") : AiExceptions.upstream("身份服务不可用"));
            var response = new MockHttpServletResponse();
            new SecurityConfig.OpsIdentityFilter(identities)
                    .doFilter(
                            new MockHttpServletRequest("GET", "/api/ai/v1/agents"),
                            response,
                            (req, res) -> fail("拒绝请求不应到达控制器"));
            assertThat(response.getStatus()).isEqualTo(expected);
        }
    }
}
