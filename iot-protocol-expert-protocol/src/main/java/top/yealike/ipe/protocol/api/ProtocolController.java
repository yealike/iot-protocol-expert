package top.yealike.ipe.protocol.api;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.protocol.codec.*;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.core.ProtocolServer;
import top.yealike.ipe.protocol.core.ProtocolType;

@RestController
@RequestMapping("/api/protocol")
public class ProtocolController {
    private final ProtocolProperties properties;
    private final java.util.List<ProtocolServer> servers;

    public ProtocolController(ProtocolProperties properties, java.util.List<ProtocolServer> servers) {
        this.properties = properties;
        this.servers = servers;
    }

    /** 当前平台注册的协议与监听状态。 */
    @GetMapping("/servers")
    public Object servers() {
        return servers.stream()
                .map(server -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("protocol", server.protocol().code());
                    value.put("label", server.protocol().label());
                    value.put("transport", server.protocol().transport());
                    value.put("port", server.port());
                    value.put("enabled", server.enabled());
                    value.put("running", server.isRunning());
                    return value;
                })
                .toList();
    }

    @GetMapping("/types")
    public Object types() {
        return Arrays.stream(ProtocolType.values())
                .map(type -> Map.of("code", type.code(), "label", type.label(), "transport", type.transport()))
                .toList();
    }

    @GetMapping("/catalog")
    public Object catalog() {
        return Arrays.stream(FrameType.values())
                .map(t -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("code", t.code());
                    v.put("hex", t.hex());
                    v.put("name", t.label());
                    v.put("direction", t.direction());
                    try {
                        v.put("fields", BmsFrames.fields(t));
                        v.put("bodyLength", BmsFrames.bodyLength(t));
                        v.put("bitDefinitions", BmsFrames.bitDefinitions(t));
                    } catch (ProtocolException ignored) {
                    }
                    return v;
                })
                .toList();
    }

    public record DecodeRequest(String wireHex, Integer frameType, String bodyHex) {}

    @PostMapping("/decode")
    public Object decode(@RequestBody DecodeRequest r) {
        if (r.wireHex() != null) {
            Frame f = FrameCodec.decode(
                    FrameCodec.parseHex(r.wireHex()), TripleDes.keyFromHex(properties.getCryptoKeyHex()));
            return Map.of("frame", f, "decoded", FramePayloads.decode(FrameType.of(f.frameType()), f.body()));
        }
        return FramePayloads.decode(FrameType.of(r.frameType()), FrameCodec.parseHex(r.bodyHex()));
    }
}
