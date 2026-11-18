package top.yealike.pep.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.codec.*;
import top.yealike.pep.codec.frames.BmsFrames;
import top.yealike.pep.gateway.config.ProtocolProperties;
import top.yealike.pep.gateway.core.ProtocolServer;
import top.yealike.pep.gateway.core.ProtocolType;

@RestController
@RequestMapping("/api/protocol")
@Tag(name = "协议通道", description = "查询协议服务与报文目录，并提供报文解码调试入口")
public class ProtocolController {
    private final ProtocolProperties properties;
    private final java.util.List<ProtocolServer> servers;

    public ProtocolController(ProtocolProperties properties, java.util.List<ProtocolServer> servers) {
        this.properties = properties;
        this.servers = servers;
    }

    /** 当前平台注册的协议与监听状态。 */
    @GetMapping("/servers")
    @Operation(summary = "查询协议服务状态")
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
    @Operation(summary = "查询支持的协议类型")
    public Object types() {
        return Arrays.stream(ProtocolType.values())
                .map(type -> Map.of("code", type.code(), "label", type.label(), "transport", type.transport()))
                .toList();
    }

    @GetMapping("/catalog")
    @Operation(summary = "查询协议报文目录")
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

    public record DecodeRequest(
            @Schema(description = "完整线上报文的十六进制字符串；提供后优先解码此字段", example = "68...")
            String wireHex,

            @Schema(description = "帧类型编码；仅在未提供 wireHex 时使用", example = "1")
            Integer frameType,

            @Schema(description = "帧体十六进制字符串；仅在未提供 wireHex 时使用", example = "010203")
            String bodyHex) {}

    @PostMapping("/decode")
    @Operation(summary = "解码协议报文", description = "支持完整线上报文解码，或按帧类型解码帧体")
    public Object decode(@RequestBody DecodeRequest r) {
        if (r.wireHex() != null && !r.wireHex().isBlank()) {
            Frame f = FrameCodec.decode(
                    FrameCodec.parseHex(r.wireHex()), TripleDes.keyFromHex(properties.getCryptoKeyHex()));
            return Map.of("frame", f, "decoded", FramePayloads.decode(FrameType.of(f.frameType()), f.body()));
        }
        if (r.frameType() == null || r.bodyHex() == null || r.bodyHex().isBlank()) {
            throw new IllegalArgumentException("wireHex 或 frameType + bodyHex 至少提供一组");
        }
        return FramePayloads.decode(FrameType.of(r.frameType()), FrameCodec.parseHex(r.bodyHex()));
    }
}
