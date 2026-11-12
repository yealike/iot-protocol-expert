package top.yealike.ipe.simulator.multidevice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MultiDeviceController {
    private final MultiDeviceRuntime runtime;
    private final String token;

    public record Command(String requestId, String device, String action) {}

    public record Scenario(String action) {}

    public MultiDeviceController(MultiDeviceRuntime runtime, @Value("${IPE_SIM_COMMAND_TOKEN:}") String token) {
        this.runtime = runtime;
        this.token = token;
    }

    @GetMapping("/api/multi/state")
    public Map<String, Object> state() {
        return runtime.snapshot();
    }

    @PostMapping("/command")
    public Map<String, Object> command(
            @RequestHeader(value = "X-Simulation-Token", required = false) String provided,
            @RequestBody Command request) {
        authorize(provided);
        try {
            return runtime.command(request.requestId(), request.device(), request.action());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/api/multi/scenario")
    public Map<String, Object> scenario(
            @RequestHeader(value = "X-Simulation-Token", required = false) String provided,
            @RequestBody Scenario request) {
        authorize(provided);
        try {
            runtime.scenario(request.action());
            return runtime.snapshot();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private void authorize(String provided) {
        if (token == null
                || token.isBlank()
                || provided == null
                || !MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
