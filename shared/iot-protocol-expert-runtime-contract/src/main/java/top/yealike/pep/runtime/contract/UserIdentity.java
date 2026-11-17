package top.yealike.pep.runtime.contract;

import java.util.List;

/** Identity supplied by the OPS identity service. */
public record UserIdentity(String id, String username, List<String> roles, List<String> permissions, long authVersion)
        implements java.security.Principal {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public String actor() {
        return id;
    }

    @Override
    public String getName() {
        return username;
    }
}
