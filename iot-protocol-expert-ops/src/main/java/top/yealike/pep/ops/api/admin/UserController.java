package top.yealike.pep.ops.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.access.domain.Identity;
import top.yealike.pep.ops.access.domain.Role;
import top.yealike.pep.ops.access.domain.UserChange;
import top.yealike.pep.ops.access.service.UserAdministrationService;
import top.yealike.pep.ops.api.admin.dto.RoleChange;

@RestController
@RequestMapping(path = "/api/ops/users", produces = "application/json")
@PreAuthorize("hasAuthority('iam:user:manage')")
@Tag(name = "用户与角色", description = "平台用户与角色的查询和维护")
@SecurityRequirement(name = "sessionCookie")
public class UserController {
    private final UserAdministrationService users;

    public UserController(UserAdministrationService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(operationId = "listUsers", summary = "查询用户列表")
    public List<Identity> list() {
        return users.list();
    }

    @GetMapping("/roles")
    @Operation(operationId = "listRoles", summary = "查询角色及权限")
    public List<Role> roles() {
        return users.roles();
    }

    @PutMapping("/roles/{code}")
    @Operation(operationId = "updateRolePermissions", summary = "更新角色权限")
    public Role updateRole(
            @Parameter(description = "角色编码") @PathVariable String code,
            @RequestBody RoleChange change,
            Authentication actor) {
        return users.updateRole(code, change.permissions(), actor.getName());
    }

    @PostMapping
    @Operation(operationId = "createUser", summary = "创建用户")
    public Identity create(@RequestBody UserChange change, Authentication actor) {
        return users.create(change, actor.getName());
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateUser", summary = "更新用户")
    public Identity update(
            @Parameter(description = "用户 ID") @PathVariable long id,
            @RequestBody UserChange change,
            Authentication actor) {
        return users.update(id, change, actor.getName());
    }
}
