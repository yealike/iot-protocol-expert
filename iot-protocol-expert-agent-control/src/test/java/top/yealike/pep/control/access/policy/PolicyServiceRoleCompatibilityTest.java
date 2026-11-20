package top.yealike.pep.control.access.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.yealike.pep.control.agents.mapper.PermissionPolicyMapper;
import top.yealike.pep.control.config.AiProperties;
import top.yealike.pep.runtime.contract.UserIdentity;

class PolicyServiceRoleCompatibilityTest {
    @Test
    void currentIamRolesUseLegacyPoliciesWithoutExpandingTheirScope() {
        var mapper = mock(PermissionPolicyMapper.class);
        when(mapper.selectEnabled("test"))
                .thenReturn(List.of(
                        new PolicyService.Rule("ADMIN", Set.of("admin-agent"), Set.of("admin-tool"), Set.of("*")),
                        new PolicyService.Rule("OPS", Set.of("ops-agent"), Set.of("read-tool"), Set.of("station-1"))));
        when(mapper.selectLatestVersion()).thenReturn(List.of("v1"));
        var policy = new PolicyService(mapper, new AiProperties("test", null, 1000, null, null, null, null));
        var admin = identity("PLATFORM_ADMIN");
        var operator = identity("OPERATOR");

        assertThat(policy.canUseAgent(admin, "admin-agent").allowed()).isTrue();
        assertThat(policy.canUseTool(operator, "ops-agent", "read-tool").allowed())
                .isTrue();
        assertThat(policy.stationScope(operator)).containsExactly("station-1");
        assertThat(policy.canUseTool(operator, "ops-agent", "admin-tool").allowed())
                .isFalse();
        assertThat(policy.canUseAgent(identity("ANALYST"), "admin-agent").allowed())
                .isFalse();
        assertThat(policy.canUseAgent(identity("ADMIN"), "admin-agent").allowed())
                .isTrue();
    }

    private static UserIdentity identity(String role) {
        return new UserIdentity("1", "test-user", List.of(role), List.of("ai:use"), 0);
    }
}
