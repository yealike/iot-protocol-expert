package top.yealike.ipe.ops.api.admin;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.diagnostic.DiagnosticService;
import top.yealike.ipe.ops.diagnostic.FrameDecodeService;
import top.yealike.ipe.ops.domain.TroubleCase;

@RestController
@RequestMapping("/api/ops/diagnostics")
public class DiagnosticController {

    private final DiagnosticService diagnostics;
    private final FrameDecodeService decoder;

    public DiagnosticController(DiagnosticService diagnostics, FrameDecodeService decoder) {
        this.diagnostics = diagnostics;
        this.decoder = decoder;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ops:operate')")
    public DiagnosticService.Result diagnose(@RequestBody DiagnosticService.Request request, Principal principal) {
        return diagnostics.diagnose(new DiagnosticService.Request(
                request.deviceId(),
                request.sn(),
                request.protocol(),
                request.protocolDeviceId(),
                request.orderNo(),
                request.raw(),
                request.question(),
                request.from(),
                request.to(),
                principal.getName()));
    }

    @PostMapping("/decode")
    public Map<String, Object> decode(@RequestBody Decode request) {
        return decoder.decode(request.raw(), request.protocol());
    }

    @GetMapping("/cases")
    public List<TroubleCase> cases() {
        return diagnostics.recent();
    }

    public record Decode(String raw, String protocol) {}
}
