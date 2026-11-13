package top.yealike.ipe.ops.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class UserAdministrationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final UserAdministrationService service =
            new UserAdministrationService(jdbc, mock(PasswordEncoder.class), identities);

    @Test
    void cannotRemoveOwnAdminRole() {
        when(identities.byId(1))
                .thenReturn(Optional.of(new IdentityService.Identity(
                        1, "admin", true, true, 0, List.of("ADMIN"), List.of("iam:user:manage"), List.of())));
        var change = new UserAdministrationService.Change("admin", null, true, List.of("VIEWER"), true, List.of());
        assertThrows(ResponseStatusException.class, () -> service.update(1, change, "admin"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void adminRoleCannotLoseManagementPermission() {
        assertThrows(ResponseStatusException.class, () -> service.updateRole("ADMIN", List.of("ops:read"), "admin"));
    }

    @Test
    void aiAdministrationRequiresAiUse() {
        assertThrows(ResponseStatusException.class, () -> service.updateRole("OPS", List.of("ai:admin"), "admin"));
    }
}
