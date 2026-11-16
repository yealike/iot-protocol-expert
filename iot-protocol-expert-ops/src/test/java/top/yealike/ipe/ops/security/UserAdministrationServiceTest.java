package top.yealike.ipe.ops.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.ipe.ops.mapper.AuditLogMapper;
import top.yealike.ipe.ops.mapper.IdentityMapper;

class UserAdministrationServiceTest {
    private final IdentityMapper iam = mock(IdentityMapper.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final UserAdministrationService service =
            new UserAdministrationService(iam, mock(PasswordEncoder.class), identities, mock(AuditLogMapper.class));

    @Test
    void cannotRemoveOwnAdminRole() {
        when(identities.byId(1))
                .thenReturn(Optional.of(new IdentityService.Identity(
                        1, "admin", true, true, 0, List.of("ADMIN"), List.of("iam:user:manage"), List.of())));
        var change = new UserAdministrationService.Change("admin", null, true, List.of("VIEWER"), true, List.of());
        assertThrows(ResponseStatusException.class, () -> service.update(1, change, "admin"));
        verify(iam, never()).updateUserAccess(anyLong(), anyBoolean(), anyBoolean());
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
