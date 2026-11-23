package top.yealike.pep.gateway.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.codec.*;
import top.yealike.pep.codec.frames.BmsFrames;
import top.yealike.pep.gateway.api.dto.DecodeRequest;
import top.yealike.pep.gateway.config.properties.ProtocolProperties;

@RestController
@RequestMapping(path = "/api/protocol", produces = "application/json")
@Tag(name = "协议通道", description = "查询协议服务与报文目录，并提供报文解码调试入口")
public class ProtocolController {
    private final ProtocolProperties properties;

    public ProtocolController(ProtocolProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/catalog")
    @Operation(operationId = "listProtocolCatalog", summary = "查询协议报文目录")
    @ApiResponse(responseCode = "200", description = "协议报文目录")
    public List<Map<String, Object>> catalog() {
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

    @PostMapping("/decode")
    @Operation(
            operationId = "decodeProtocolFrame",
            summary = "解码协议报文",
            description = "支持完整线上报文解码，或按帧类型解码帧体；响应为对应帧的字段结构")
    @ApiResponse(
            responseCode = "200",
            description = "解码结果",
            content = @Content(schema = @Schema(description = "完整报文返回 frame/decoded，帧体解码返回字段映射")))
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
