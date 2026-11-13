package top.yealike.ipe.ops.api.internal;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import top.yealike.ipe.ops.security.IdentityService;

/**
 * Looks up the current browser session by the ID authenticated by the AI service.
 */
@Component
public class OpsSessionLookup implements HttpSessionListener, HttpSessionIdListener {
    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
    private final IdentityService identities;

    public OpsSessionLookup(IdentityService identities) {
        this.identities = identities;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        var session = event.getSession();
        sessions.put(session.getId(), session);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        var session = event.getSession();
        sessions.remove(session.getId(), session);
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        sessions.remove(oldSessionId, event.getSession());
        sessionCreated(event);
    }

    public Map<String, Object> resolve(String id) {
        if (id == null) return null;
        HttpSession session = sessions.get(id);
        if (session == null) return null;
        try {
            // Attribute reads do not renew the container's session inactivity timer.
            long idleMs = System.currentTimeMillis() - session.getLastAccessedTime();
            int interval = session.getMaxInactiveInterval();
            if (interval > 0 && idleMs >= interval * 1000L) return null;
            if (!(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                instanceof SecurityContext context)) return null;
            var auth = context.getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            return identities
                .byUsername(auth.getName())
                .filter(IdentityService.Identity::enabled)
                .filter(identity -> Objects.equals(identity.authVersion(), session.getAttribute("iam.authVersion")))
                .map(identity -> Map.<String, Object>of(
                    "authenticated",
                    true,
                    "id",
                    identity.id(),
                    "username",
                    identity.username(),
                    "roles",
                    identity.roles(),
                    "permissions",
                    identity.permissions(),
                    "authVersion",
                    identity.authVersion()))
                .orElse(null);
        } catch (IllegalStateException expired) {
            return null;
        }
    }
}
