package top.yealike.ipe.ai.identity;

import java.util.List;

/** Identity supplied by the OPS identity service. */
public record UserIdentity(String id, String username, List<String> roles, List<String> permissions, long authVersion) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public String actor() {
        return id;
    }
}
