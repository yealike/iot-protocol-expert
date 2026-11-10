package top.yealike.ipe.protocol.api;

import top.yealike.ipe.protocol.codec.*;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/protocol")
public class ProtocolController {
    private final ProtocolProperties properties;

    public ProtocolController(ProtocolProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/catalog")
    public Object catalog() {
        return Arrays.stream(FrameType.values()).map(t -> {
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
        }).toList();
    }

    public record DecodeRequest(String wireHex, Integer frameType, String bodyHex) {
    }

    @PostMapping("/decode")
    public Object decode(@RequestBody DecodeRequest r) {
        if (r.wireHex() != null) {
            Frame f = FrameCodec.decode(FrameCodec.parseHex(r.wireHex()), TripleDes.keyFromHex(properties.getCryptoKeyHex()));
            return Map.of("frame", f, "decoded", FramePayloads.decode(FrameType.of(f.frameType()), f.body()));
        }
        return FramePayloads.decode(FrameType.of(r.frameType()), FrameCodec.parseHex(r.bodyHex()));
    }
}
