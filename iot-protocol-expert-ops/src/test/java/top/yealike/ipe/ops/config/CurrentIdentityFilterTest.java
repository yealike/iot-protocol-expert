package top.yealike.ipe.ops.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.yealike.ipe.ops.security.IdentityService;
import top.yealike.ipe.ops.security.IdentityService.Identity;

class CurrentIdentityFilterTest {
    private final IdentityService identities = mock(IdentityService.class);
    private final SecurityConfig.CurrentIdentityFilter filter = new SecurityConfig.CurrentIdentityFilter(identities);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String uri, long sessionVersion) {
        var req = new MockHttpServletRequest("GET", uri);
        req.getSession().setAttribute("iam.authVersion", sessionVersion);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        return req;
    }

    @Test
    void revocationInvalidatesExistingSession() throws Exception {
        var request = request("/api/ops/devices", 1);
        when(identities.byUsername("alice"))
                .thenReturn(Optional.of(
                        new Identity(1, "alice", true, true, 2, List.of("VIEWER"), List.of("ops:read"), List.of())));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void restrictedUserCannotBypassUnscopedOpsEndpoints() throws Exception {
        var request = request("/api/ops/devices", 2);
        when(identities.byUsername("alice"))
                .thenReturn(Optional.of(
                        new Identity(1, "alice", true, false, 2, List.of("VIEWER"), List.of("ops:read"), List.of(3L))));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }

    @Test
    void roleAuthoritiesAreRefreshedOnEachRequest() throws Exception {
        var request = request("/api/ops/auth/me", 2);
        when(identities.byUsername("alice"))
                .thenReturn(Optional.of(new Identity(
                        1,
                        "alice",
                        true,
                        true,
                        2,
                        List.of("OPS", "VIEWER"),
                        List.of("ops:read", "ops:operate"),
                        List.of())));
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ops:operate")));
    }
}
