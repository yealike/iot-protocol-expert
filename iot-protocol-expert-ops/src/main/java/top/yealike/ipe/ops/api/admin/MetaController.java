package top.yealike.ipe.ops.api.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops")
public class MetaController {

    private final top.yealike.ipe.ops.service.AuditService audits;

    public MetaController(top.yealike.ipe.ops.service.AuditService audits) {
        this.audits = audits;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", authentication.getName());
        me.put(
                "roles",
                authentication.getAuthorities().stream().map(Object::toString).toList());
        return me;
    }

    @GetMapping("/audits")
    public List<?> audits() {
        return audits.recent();
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "protocols",
                        List.of(
                                Map.of(
                                        "code",
                                        "YKC16",
                                        "label",
                                        "云快充 1.6",
                                        "transport",
                                        "TCP",
                                        "version",
                                        "1.6",
                                        "listen",
                                        "8768"),
                                Map.of(
                                        "code",
                                        "OCPP16J",
                                        "label",
                                        "OCPP 1.6J",
                                        "transport",
                                        "WebSocket",
                                        "version",
                                        "1.6",
                                        "listen",
                                        "8769")),
                "roles", List.of("VIEWER", "OPS", "ADMIN"),
                "commandTypes",
                        List.of(
                                "remote-start",
                                "remote-stop",
                                "read-realtime",
                                "time-sync",
                                "param-set",
                                "reboot",
                                "update",
                                "rate-model",
                                "balance",
                                "cards-sync",
                                "cards-clear",
                                "cards-query",
                                "lock",
                                "reset",
                                "unlock",
                                "configuration",
                                "availability",
                                "trigger",
                                "clear-cache",
                                "data-transfer",
                                "diagnostics",
                                "firmware",
                                "raw-call"));
    }
}
