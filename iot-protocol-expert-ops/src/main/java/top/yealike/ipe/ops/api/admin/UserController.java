package top.yealike.ipe.ops.api.admin;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.security.IdentityService.Identity;
import top.yealike.ipe.ops.security.UserAdministrationService;
import top.yealike.ipe.ops.security.UserAdministrationService.Change;

@RestController
@RequestMapping("/api/ops/users")
@PreAuthorize("hasAuthority('iam:user:manage')")
public class UserController {
    private final UserAdministrationService users;

    public UserController(UserAdministrationService users) {
        this.users = users;
    }

    @GetMapping
    public List<Identity> list() {
        return users.list();
    }

    public record RoleChange(List<String> permissions) {}

    @GetMapping("/roles")
    public List<UserAdministrationService.Role> roles() {
        return users.roles();
    }

    @PutMapping("/roles/{code}")
    public UserAdministrationService.Role updateRole(
            @PathVariable String code, @RequestBody RoleChange change, Authentication actor) {
        return users.updateRole(code, change.permissions(), actor.getName());
    }

    @PostMapping
    public Identity create(@RequestBody Change change, Authentication actor) {
        return users.create(change, actor.getName());
    }

    @PutMapping("/{id}")
    public Identity update(@PathVariable long id, @RequestBody Change change, Authentication actor) {
        return users.update(id, change, actor.getName());
    }
}
