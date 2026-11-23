package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.yealike.pep.ops.access.domain.Identity;
import top.yealike.pep.ops.access.service.IdentityService;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.dto.BillView;
import top.yealike.pep.ops.charging.service.BillService;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.devices.domain.DeviceChannel;
import top.yealike.pep.ops.devices.domain.DeviceInstance;
import top.yealike.pep.ops.devices.domain.DeviceProtocolMapping;
import top.yealike.pep.ops.devices.domain.FrameLog;
import top.yealike.pep.ops.devices.domain.Product;
import top.yealike.pep.ops.devices.domain.ProtocolDocumentRef;
import top.yealike.pep.ops.devices.dto.DeviceDetail;
import top.yealike.pep.ops.devices.dto.DeviceRow;
import top.yealike.pep.ops.devices.mapper.DeviceChannelMapper;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.mapper.ProtocolDocumentMapper;
import top.yealike.pep.ops.devices.service.DeviceInstanceService;
import top.yealike.pep.ops.devices.service.FrameLogService;
import top.yealike.pep.ops.devices.service.ProductService;
import top.yealike.pep.ops.diagnostic.service.FrameDecodeService;
import top.yealike.pep.ops.telemetry.domain.TelemetryPoint;
import top.yealike.pep.ops.telemetry.domain.TelemetryResult;
import top.yealike.pep.ops.telemetry.service.OrderTelemetryService;

/**
 * Read-only internal HTTP endpoints mapped to tools by the gateway.
 */
@RestController
@RequestMapping(path = "/internal/ai/rest", consumes = "application/json", produces = "application/json")
@Tag(name = "AI 工具接口", description = "Higress REST-to-MCP 映射的只读业务工具")
@SecurityRequirement(name = "opsServiceToken")
public class OpsAiRestController {

    private static final int DOCUMENT_CHUNK_BYTES = 128 * 1024;
    private static final java.util.regex.Pattern STORAGE_KEY = java.util.regex.Pattern.compile("[0-9a-f-]{36}");

    private final OrderService orders;
    private final BillService bills;
    private final DeviceInstanceService devices;
    private final DeviceInstanceMapper deviceRecords;
    private final DeviceChannelMapper channels;
    private final DeviceProtocolMappingMapper mappings;
    private final ProductService products;
    private final FrameLogService frames;
    private final FrameDecodeService decoder;
    private final OrderTelemetryService telemetry;
    private final ProtocolDocumentMapper documents;
    private final IdentityService identities;
    private final OpsSessionLookup sessions;
    private final AiContextCodec contexts;
    private final String documentDirectory;

    public OpsAiRestController(
            OrderService orders,
            BillService bills,
            DeviceInstanceService devices,
            DeviceInstanceMapper deviceRecords,
            DeviceChannelMapper channels,
            DeviceProtocolMappingMapper mappings,
            ProductService products,
            FrameLogService frames,
            FrameDecodeService decoder,
            OrderTelemetryService telemetry,
            ProtocolDocumentMapper documents,
            IdentityService identities,
            OpsSessionLookup sessions,
            AiContextCodec contexts,
            @Value("${ipe.ops.protocol-document-dir:./data/protocol-documents}") String documentDirectory) {
        this.orders = orders;
        this.bills = bills;
        this.devices = devices;
        this.deviceRecords = deviceRecords;
        this.channels = channels;
        this.mappings = mappings;
        this.products = products;
        this.frames = frames;
        this.decoder = decoder;
        this.telemetry = telemetry;
        this.documents = documents;
        this.identities = identities;
        this.sessions = sessions;
        this.contexts = contexts;
        this.documentDirectory = documentDirectory;
    }

    @PostMapping("/resolve_session")
    @Operation(operationId = "aiResolveSession", summary = "解析会话主体")
    public Map<String, Object> session(@RequestBody Map<String, Object> args) {
        var identity = sessions.resolve(context("resolve_session", args).session());
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return identity;
    }

    /**
     * Resolve an immutable user ID for queued runs and authorization checks.
     */
    @PostMapping("/resolve_actor")
    @Operation(operationId = "aiResolveActor", summary = "解析操作账号")
    public Identity userIdentity(@RequestBody Map<String, Object> args) {
        context("resolve_actor", args);
        return identities
                .byId(number(args, "id"))
                .filter(Identity::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/get_order_detail")
    @Operation(operationId = "aiGetOrderDetail", summary = "查询订单详情")
    public Map<String, Object> order(@RequestBody Map<String, Object> args) {
        var context = context("get_order_detail", args);
        requireActor(context);
        String orderNo = string(args, "order_no");
        ChargeOrder order = orders.require(orderNo);
        DeviceDetail device = devices.detail(order.deviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", orders.view(order));
        data.put("bills", bills.byOrder(order.id).stream().map(bills::view).toList());
        data.put("device", deviceSummary(device.device()));
        return envelope(data, false, List.of());
    }

    @PostMapping("/get_order_bills")
    @Operation(operationId = "aiGetOrderBills", summary = "查询订单账单")
    public Map<String, Object> orderBills(@RequestBody Map<String, Object> args) {
        var context = context("get_order_bills", args);
        requireActor(context);
        String orderNo = string(args, "order_no");
        ChargeOrder order = orders.require(orderNo);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.orderNo);
        data.put("amount_source", order.amountSource);
        data.put("bills", bills.byOrder(order.id).stream().map(this::billView).toList());
        return envelope(data, false, List.of());
    }

    @PostMapping("/get_device_detail")
    @Operation(operationId = "aiGetDeviceDetail", summary = "查询设备详情")
    public Map<String, Object> device(@RequestBody Map<String, Object> args) {
        var context = context("get_device_detail", args);
        requireActor(context);
        long id = number(args, "device_id");
        DeviceDetail detail = devices.detail(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device", deviceSummary(detail.device()));
        data.put(
                "channels",
                detail.channels().stream()
                        .map(channel -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("gun_no", channel.gunNo());
                            item.put("platform_gun_no", channel.platformGunNo());
                            item.put("protocol_connector_no", channel.protocolConnectorNo());
                            item.put("name", channel.name());
                            item.put("enabled", channel.enabled());
                            return item;
                        })
                        .toList());
        return envelope(data, false, List.of());
    }

    /**
     * 以平台标识定位设备。8 位和 10 位编号只在平台内解释；其他编号必须通过显式协议映射查找，
     * 不允许把协议侧编号模糊回退成平台 SN。
     */
    @PostMapping("/resolve_device")
    @Operation(operationId = "aiResolveDevice", summary = "解析设备")
    public Map<String, Object> resolveDevice(@RequestBody Map<String, Object> args) {
        var context = context("resolve_device", args);
        requireActor(context);
        String identifier = requiredText(args, "identifier", 128);
        String protocolHint = normalizeProtocolHint(optionalText(args, "protocol_hint", 32));

        DeviceTarget target;
        if (identifier.matches("\\d{8}")) {
            DeviceInstance exact = deviceRecords.selectByDeviceCode(identifier);
            if (exact == null) {
                throw new AiToolFailure(404, "DEVICE_SN_NOT_FOUND", "平台设备 SN 不存在");
            }
            target = new DeviceTarget(exact, mappingForDevice(exact.id), null, "PLATFORM_DEVICE_SN");
        } else if (identifier.matches("\\d{10}")) {
            String gunNo = identifier.substring(8);
            if ("00".equals(gunNo)) {
                throw new AiToolFailure(400, "INVALID_PLATFORM_GUN_NO", "平台枪号必须从 01 开始");
            }
            target = resolvePlatformGun(identifier, identifier.substring(0, 8), gunNo);
        } else {
            target = resolveThirdPartyDevice(identifier, protocolHint);
        }
        return envelope(deviceContext(target), false, List.of());
    }

    /**
     * 最近/指定时间窗报文列表。该工具只接受平台 SN，只返回短摘要；完整 HEX 必须通过单帧解码工具获取。
     */
    @PostMapping("/list_device_frames")
    @Operation(operationId = "aiListDeviceFrames", summary = "查询设备协议报文")
    public Map<String, Object> listDeviceFrames(@RequestBody Map<String, Object> args) {
        var context = context("list_device_frames", args);
        requireActor(context);
        String deviceSn = requiredText(args, "device_sn", 128);
        DeviceInstance device = requirePlatformDevice(deviceSn);
        String protocol = deviceProtocol(device);

        String direction = normalizeDirection(optionalText(args, "direction", 16));
        LocalDateTime from = timeArgument(args, "from");
        LocalDateTime to = timeArgument(args, "to");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new AiToolFailure(400, "INVALID_TIME_RANGE", "from 必须早于 to");
        }
        FrameFilter filter = frameFilter(protocol, optionalText(args, "frame_type", 64));
        int limit = optionalInteger(args, "limit", 20);
        if (limit < 1) {
            throw new AiToolFailure(400, "INVALID_LIMIT", "limit 必须在 1 到 50 之间");
        }
        limit = Math.min(limit, 50);
        List<FrameLog> matched =
                frames.searchForAi(device.id, direction, filter.frameType(), filter.frameLabel(), from, to, limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device_sn", device.deviceCode);
        data.put("protocol_code", protocol);
        data.put("from", from);
        data.put("to", to);
        data.put("direction", direction);
        data.put("returned", matched.size());
        data.put("frames", matched.stream().map(this::frameSummary).toList());
        List<String> warnings = matched.isEmpty() ? List.of("当前查询条件下没有匹配报文") : List.of();
        return envelope(data, false, warnings);
    }

    /**
     * 解码单个持久化帧或用户贴入的原始帧。协议由设备所属产品决定，模型不能指定其他协议解码器。
     */
    @PostMapping("/decode_device_frame")
    @Operation(operationId = "aiDecodeDeviceFrame", summary = "解码协议报文")
    public Map<String, Object> decodeDeviceFrame(@RequestBody Map<String, Object> args) {
        var context = context("decode_device_frame", args);
        requireActor(context);
        String deviceSn = requiredText(args, "device_sn", 128);
        DeviceInstance device = requirePlatformDevice(deviceSn);
        String protocol = deviceProtocol(device);

        Long frameId = optionalLong(args, "frame_id");
        String rawFrame = optionalText(args, "raw_frame", 16 * 1024);
        if ((frameId == null) == (rawFrame == null)) {
            throw new AiToolFailure(400, "INVALID_FRAME_SELECTOR", "frame_id 与 raw_frame 必须且只能提供一个");
        }

        Map<String, Object> decoded;
        Object raw;
        String rawSource;
        Map<String, Object> metadata;
        List<String> warnings = new ArrayList<>();
        String storedFrameError = null;
        if (frameId != null) {
            FrameLog frame = frames.findForDevice(device.id, frameId)
                    .orElseThrow(() -> new AiToolFailure(404, "FRAME_NOT_FOUND", "该设备下不存在指定帧"));
            if (frame.protocol != null
                    && !DeviceInstanceService.normalizeProtocol(frame.protocol).equals(protocol)) {
                throw new AiToolFailure(409, "FRAME_PROTOCOL_MISMATCH", "帧协议与设备产品协议不一致");
            }
            decoded = decoder.decodeStored(
                    protocol, frame.frameType, frame.bodyHex, frame.frameLabel, frame.messageId, frame.errorMessage);
            raw = frame.wireHex == null || frame.wireHex.isBlank() ? frame.bodyHex : frame.wireHex;
            rawSource = frame.wireHex == null || frame.wireHex.isBlank() ? "STORED_BODY_HEX" : "STORED_WIRE_HEX";
            storedFrameError = frame.errorMessage;
            metadata = frameMetadata(frame);
            if (frame.errorMessage != null && !frame.errorMessage.isBlank()) {
                warnings.add("原始帧存在网关记录错误: " + frame.errorMessage);
            }
        } else {
            decoded = decoder.decode(rawFrame, protocol);
            raw = rawFrame;
            rawSource = "USER_INPUT";
            metadata = new LinkedHashMap<>();
            metadata.put("frame_id", null);
            metadata.put("frame_type", null);
            metadata.put("frame_label", null);
            metadata.put("direction", null);
            metadata.put("created_at", null);
        }
        if (!Boolean.TRUE.equals(decoded.get("ok"))) {
            warnings.add(String.valueOf(decoded.getOrDefault("error", "报文解码失败")));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device_sn", device.deviceCode);
        data.put("protocol_code", protocol);
        data.put("frame", metadata);
        data.put("raw", raw);
        data.put("raw_source", rawSource);
        data.put("fields", decoded.get("fields"));
        data.put("decoded", decoded);
        data.put("error", decoded.get("error") == null ? storedFrameError : decoded.get("error"));
        return envelope(data, !warnings.isEmpty(), List.copyOf(warnings));
    }

    @PostMapping("/get_order_telemetry_summary")
    @Operation(operationId = "aiGetOrderTelemetrySummary", summary = "汇总订单遥测")
    public Map<String, Object> telemetrySummary(@RequestBody Map<String, Object> args) {
        var context = context("get_order_telemetry_summary", args);
        requireActor(context);
        String orderNo = string(args, "order_no");
        Integer windowHours = args.containsKey("window_hours") ? Math.toIntExact(number(args, "window_hours")) : null;
        ChargeOrder order = orders.require(orderNo);
        TelemetryResult result;
        try {
            result = telemetry.query(orderNo);
        } catch (IllegalStateException exception) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("order_no", orderNo);
            data.put("available", false);
            return envelope(data, true, List.of("订单时序数据正在同步，请稍后重试"));
        }
        List<TelemetryPoint> points = result.points();
        if (windowHours != null && windowHours > 0 && !points.isEmpty()) {
            Instant threshold = Instant.now().minusSeconds(windowHours * 3600L);
            points = points.stream()
                    .filter(point -> point.time() != null && point.time().isAfter(threshold))
                    .toList();
        }
        TelemetryPoint first = firstWithSoc(points);
        TelemetryPoint last = lastWithSoc(points);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", orderNo);
        data.put("available", true);
        data.put("sample_count", points.size());
        data.put("truncated", result.truncated());
        data.put(
                "first_sample_time", points.isEmpty() ? null : points.getFirst().time());
        data.put("last_sample_time", points.isEmpty() ? null : points.getLast().time());
        data.put("first_soc", socValue(first));
        data.put("first_soc_time", first == null ? null : first.time());
        data.put("last_soc", socValue(last));
        data.put("last_soc_time", last == null ? null : last.time());
        data.put("soc_note", "首末 SOC 是首末有效采样值，不是精确的开始/结束值；采样点缺失时返回空，不做推断");
        List<String> warnings = new ArrayList<>();
        if (first == null) {
            warnings.add("该订单没有有效 SOC 采样");
        }
        if (result.truncated()) {
            warnings.add("采样点过多，仅统计最近一部分");
        }
        return envelope(data, result.truncated(), warnings);
    }

    @PostMapping("/list_documents")
    @Operation(operationId = "aiListDocuments", summary = "列出协议文档")
    public Map<String, Object> documents(@RequestBody Map<String, Object> args) {
        requireAiAdmin(context("list_documents", args));
        return envelope(documents.selectActiveForAi(), false, List.of());
    }

    @PostMapping("/read_document")
    @Operation(operationId = "aiReadDocument", summary = "读取协议文档")
    public Map<String, Object> readDocument(@RequestBody Map<String, Object> args) {
        requireAiAdmin(context("read_document", args));
        long id = number(args, "id");
        long offset = number(args, "offset");
        if (id <= 0 || offset < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String storageKey = documents.selectActiveStorageKey(id);
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Path file;
        try {
            Path root = Paths.get(documentDirectory).toRealPath();
            file = root.resolve(storageKey).toRealPath();
            if (!file.startsWith(root)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try (var channel = Files.newByteChannel(file)) {
            long size = channel.size();
            if (offset > size) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            channel.position(offset);
            try (var stream = java.nio.channels.Channels.newInputStream(channel)) {
                byte[] bytes = stream.readNBytes(DOCUMENT_CHUNK_BYTES);
                return Map.of(
                        "base64",
                        Base64.getEncoder().encodeToString(bytes),
                        "size",
                        size,
                        "offset",
                        offset,
                        "eof",
                        offset + bytes.length == size);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    private DeviceTarget resolvePlatformGun(String platformGunNo, String deviceSn, String gunNo) {
        DeviceInstance device = deviceRecords.selectByDeviceCode(deviceSn);
        if (device == null) {
            throw new AiToolFailure(404, "DEVICE_SN_NOT_FOUND", "平台枪号对应的设备 SN 不存在");
        }
        DeviceChannel channel = channels.selectByDeviceIdAndChannelNo(device.id, gunNo);
        if (channel == null) {
            throw new AiToolFailure(404, "PLATFORM_GUN_NOT_FOUND", "设备不存在指定平台枪口: " + platformGunNo);
        }
        return new DeviceTarget(
                device,
                mappingForDevice(device.id),
                channel.platformGunNo == null ? platformGunNo : channel.platformGunNo,
                "PLATFORM_GUN_NO");
    }

    private DeviceTarget resolveThirdPartyDevice(String identifier, String protocolHint) {
        Map<String, DeviceTarget> targets = new LinkedHashMap<>();
        List<String> protocols = protocolHint == null ? List.of("YKC16", "OCPP16J") : List.of(protocolHint);
        for (String protocol : protocols) {
            DeviceProtocolMapping mapping = mappings.findByProtocolAndThirdPartyDeviceId(protocol, identifier)
                    .filter(item -> item.enabled)
                    .orElse(null);
            if (mapping == null) {
                continue;
            }
            DeviceInstance device = deviceRecords.selectById(mapping.deviceId);
            if (device == null || !protocol.equals(DeviceInstanceService.normalizeProtocol(device.protocol))) {
                continue;
            }
            putTarget(targets, new DeviceTarget(device, mapping, null, "THIRD_PARTY_DEVICE_ID"));
        }
        if (targets.isEmpty()) {
            throw new AiToolFailure(404, "IDENTIFIER_NOT_FOUND", "未找到匹配的设备或第三方设备号映射");
        }
        if (targets.size() > 1) {
            throw new AiToolFailure(
                    409,
                    "IDENTIFIER_AMBIGUOUS",
                    "该第三方设备号命中多个协议或设备，请提供 protocol_hint 或平台 SN",
                    Map.of(
                            "candidates",
                            targets.values().stream().map(this::candidateView).toList()));
        }
        return targets.values().iterator().next();
    }

    private DeviceProtocolMapping mappingForDevice(Long deviceId) {
        return mappings.findByDeviceId(deviceId).orElse(null);
    }

    private static void putTarget(Map<String, DeviceTarget> targets, DeviceTarget target) {
        String key = target.device().id + "|" + DeviceInstanceService.normalizeProtocol(target.device().protocol);
        targets.putIfAbsent(key, target);
    }

    private DeviceInstance requirePlatformDevice(String deviceSn) {
        if (!deviceSn.matches("[1-9][0-9]{7}")) {
            throw new AiToolFailure(400, "INVALID_PLATFORM_DEVICE_SN", "device_sn 必须是 8 位平台设备 SN");
        }
        DeviceInstance device = deviceRecords.selectByDeviceCode(deviceSn);
        if (device == null) {
            throw new AiToolFailure(404, "DEVICE_SN_NOT_FOUND", "平台设备 SN 不存在");
        }
        return device;
    }

    private String deviceProtocol(DeviceInstance device) {
        if (device.productId != null) {
            try {
                String protocol = DeviceInstanceService.normalizeProtocol(products.get(device.productId).protocol);
                return requireSupportedProtocol(protocol);
            } catch (IllegalArgumentException exception) {
                throw new AiToolFailure(409, "PRODUCT_NOT_FOUND", "设备绑定的产品不存在");
            }
        }
        return requireSupportedProtocol(DeviceInstanceService.normalizeProtocol(device.protocol));
    }

    private static String requireSupportedProtocol(String protocol) {
        if (!"YKC16".equals(protocol) && !"OCPP16J".equals(protocol)) {
            throw new AiToolFailure(409, "UNSUPPORTED_PROTOCOL", "设备产品协议暂不支持报文解码");
        }
        return protocol;
    }

    private Map<String, Object> deviceContext(DeviceTarget target) {
        DeviceInstance device = target.device();
        Product product = null;
        ProtocolDocumentRef protocolDocument = null;
        if (device.productId != null) {
            try {
                product = products.get(device.productId);
                if (product.protocolDocumentId != null) {
                    protocolDocument = documents.selectRef(product.protocolDocumentId);
                }
            } catch (IllegalArgumentException exception) {
                throw new AiToolFailure(409, "PRODUCT_NOT_FOUND", "设备绑定的产品不存在");
            }
        }

        String protocolCode =
                DeviceInstanceService.normalizeProtocol(product == null ? device.protocol : product.protocol);
        List<DeviceChannel> deviceChannels = channels.findByDeviceIdOrderByChannelNoAsc(device.id);
        DeviceChannel selectedChannel = target.platformGunNo() == null
                ? null
                : deviceChannels.stream()
                        .filter(channel -> target.platformGunNo().equals(channel.platformGunNo))
                        .findFirst()
                        .orElseGet(() ->
                                channelByPlatformGunNo(deviceChannels, device.deviceCode, target.platformGunNo()));

        Map<String, Object> thirdParty = new LinkedHashMap<>();
        thirdParty.put("found", target.mapping() != null);
        thirdParty.put("protocol_code", protocolCode);
        thirdParty.put("third_party_device_id", target.mapping() == null ? null : target.mapping().thirdPartyDeviceId);
        thirdParty.put("enabled", target.mapping() != null && target.mapping().enabled);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device_sn", device.deviceCode);
        data.put("platform_device_sn", device.deviceCode);
        data.put("device_name", device.name);
        data.put("product_id", product == null ? null : product.id);
        data.put("product_code", product == null ? null : product.productCode);
        data.put("product_name", product == null ? null : product.name);
        data.put("protocol_code", protocolCode);
        data.put(
                "protocol_name_en",
                protocolDocument == null ? fallbackProtocolName(protocolCode) : protocolDocument.protocolNameEn());
        data.put(
                "protocol_version",
                protocolDocument == null ? fallbackProtocolVersion(protocolCode) : protocolDocument.documentVersion());
        data.put(
                "protocol_document_id",
                product == null || product.protocolDocumentId == null ? null : product.protocolDocumentId.toString());
        data.put("lookup_type", target.lookupType());
        data.put("third_party_mapping", thirdParty);
        data.put("connectors", deviceChannels.stream().map(this::connectorView).toList());
        data.put("gun", selectedChannel == null ? null : connectorView(selectedChannel));
        data.put("state", device.getStateOrOffline());
        data.put("enabled", device.enabled);
        data.put("last_seen", device.lastSeen == null ? null : device.lastSeen.toString());
        return data;
    }

    private static DeviceChannel channelByPlatformGunNo(
            List<DeviceChannel> channels, String deviceSn, String platformGunNo) {
        if (!platformGunNo.startsWith(deviceSn) || platformGunNo.length() != deviceSn.length() + 2) {
            return null;
        }
        String gunNo = platformGunNo.substring(deviceSn.length());
        return channels.stream()
                .filter(channel -> gunNo.equals(channel.channelNo))
                .findFirst()
                .orElse(null);
    }

    private static String fallbackProtocolName(String protocol) {
        return "OCPP16J".equals(protocol) ? "OCPP" : "YKC";
    }

    private static String fallbackProtocolVersion(String protocol) {
        return "OCPP16J".equals(protocol) ? "1.6J" : "1.6";
    }

    private Map<String, Object> candidateView(DeviceTarget target) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("device_sn", target.device().deviceCode);
        candidate.put("device_name", target.device().name);
        candidate.put("protocol_code", normalizeProtocolHint(target.device().protocol));
        candidate.put("third_party_device_id", target.mapping() == null ? null : target.mapping().thirdPartyDeviceId);
        candidate.put("lookup_type", target.lookupType());
        candidate.put("platform_gun_no", target.platformGunNo());
        return candidate;
    }

    private Map<String, Object> connectorView(DeviceChannel channel) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gun_no", channel.channelNo);
        item.put("platform_gun_no", channel.platformGunNo);
        item.put("protocol_connector_no", channel.protocolConnectorNo);
        item.put("name", channel.name);
        item.put("enabled", channel.enabled);
        return item;
    }

    private Map<String, Object> frameSummary(FrameLog frame) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("frame_id", frame.id);
        item.put("time", frame.createdAt);
        item.put("direction", frame.direction);
        item.put("frame_type", frameTypeDisplay(frame));
        item.put("frame_type_code", frame.frameType);
        item.put("frame_label", frame.frameLabel);
        item.put("summary", frame.summary);
        item.put("message_id", frame.messageId);
        item.put("trace_id", frame.traceId);
        item.put("error", frame.errorMessage);
        return item;
    }

    private Map<String, Object> frameMetadata(FrameLog frame) {
        Map<String, Object> item = frameSummary(frame);
        item.put("protocol", frame.protocol);
        item.put("sequence_no", frame.sequenceNo);
        return item;
    }

    private static String frameTypeDisplay(FrameLog frame) {
        String protocol = normalizeProtocolHint(frame.protocol);
        if ("YKC16".equals(protocol)) {
            return String.format("0x%02X", frame.frameType);
        }
        if (frame.frameLabel != null && !frame.frameLabel.isBlank()) {
            return frame.frameLabel;
        }
        return switch (frame.frameType) {
            case 2 -> "CALL";
            case 3 -> "CALLRESULT";
            case 4 -> "CALLERROR";
            default -> String.valueOf(frame.frameType);
        };
    }

    private static FrameFilter frameFilter(String protocol, String requested) {
        if (requested == null) {
            return new FrameFilter(null, null);
        }
        String value = requested.trim();
        String normalizedProtocol = normalizeProtocolHint(protocol);
        if ("YKC16".equals(normalizedProtocol)) {
            Integer code = parseFrameCode(value);
            if (code == null) {
                throw new AiToolFailure(400, "INVALID_FRAME_TYPE", "云快充帧类型应为 0x01 或十进制整数");
            }
            return new FrameFilter(code, null);
        }
        Integer code = parseFrameCode(value);
        if (code != null) {
            return new FrameFilter(code, null);
        }
        return new FrameFilter(
                switch (value.toUpperCase(Locale.ROOT)) {
                    case "CALL" -> 2;
                    case "CALLRESULT" -> 3;
                    case "CALLERROR" -> 4;
                    default -> null;
                },
                value);
    }

    private static Integer parseFrameCode(String value) {
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                int parsed = Integer.parseInt(value.substring(2), 16);
                return parsed >= 0 && parsed <= 255 ? parsed : null;
            }
            if (value.matches("\\d{1,3}")) {
                int parsed = Integer.parseInt(value);
                return parsed >= 0 && parsed <= 255 ? parsed : null;
            }
        } catch (NumberFormatException ignored) {
            // 非数字帧过滤值在 OCPP 中可作为 Action 名称。
        }
        return null;
    }

    private static String normalizeDirection(String direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction.trim().toUpperCase(Locale.ROOT)) {
            case "IN", "UP" -> "IN";
            case "OUT", "DOWN" -> "OUT";
            default -> throw new AiToolFailure(400, "INVALID_DIRECTION", "direction 只支持 IN 或 OUT");
        };
    }

    private static LocalDateTime timeArgument(Map<String, Object> args, String key) {
        String value = optionalText(args, key, 64);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无时区 ISO-8601 或 Instant。
        }
        try {
            return Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试 LocalDateTime。
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new AiToolFailure(400, "INVALID_TIME", key + " 必须是 ISO-8601 时间");
        }
    }

    private static String normalizeProtocolHint(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        String normalized = DeviceInstanceService.normalizeProtocol(protocol);
        if (!"YKC16".equals(normalized) && !"OCPP16J".equals(normalized)) {
            throw new AiToolFailure(400, "UNSUPPORTED_PROTOCOL", "protocol_hint 只支持 YKC16 或 OCPP16J");
        }
        return normalized;
    }

    private static String requiredText(Map<String, Object> args, String key, int maxLength) {
        String value = optionalText(args, key, maxLength);
        if (value == null) {
            throw new AiToolFailure(400, "INVALID_ARGUMENTS", key + " 不能为空");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> args, String key, int maxLength) {
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.length() > maxLength) {
            throw new AiToolFailure(400, "INVALID_ARGUMENTS", key + " 参数格式非法");
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static int optionalInteger(Map<String, Object> args, String key, int defaultValue) {
        Object raw = args.get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            if (raw instanceof Number number) {
                return number.intValue();
            }
            if (raw instanceof String value && value.matches("\\d{1,6}")) {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException ignored) {
            // 统一转成参数错误。
        }
        throw new AiToolFailure(400, "INVALID_ARGUMENTS", key + " 必须是整数");
    }

    private static Long optionalLong(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw == null) {
            return null;
        }
        try {
            long value = raw instanceof Number number
                    ? number.longValue()
                    : raw instanceof String text && text.matches("\\d{1,19}") ? Long.parseLong(text) : -1L;
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 统一转成参数错误。
        }
        throw new AiToolFailure(400, "INVALID_ARGUMENTS", key + " 必须是正整数");
    }

    private Map<String, Object> billView(top.yealike.pep.ops.charging.domain.Bill bill) {
        BillView view = bills.view(bill);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source_type", view.sourceType());
        item.put("source_transaction_no", view.sourceTransactionNo());
        item.put("energy", view.energy());
        item.put("amount", view.amount());
        item.put("currency", view.currency());
        item.put("amount_type", view.amountType());
        item.put("stop_reason", view.stopReason());
        item.put("ended_at", view.endedAt());
        item.put("received_at", view.receivedAt());
        item.put("accepted", view.accepted());
        item.put("reject_reason", view.rejectReason());
        return item;
    }

    private Map<String, Object> deviceSummary(DeviceRow device) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", device.id());
        item.put("device_code", device.deviceSn());
        item.put("platform_device_sn", device.deviceSn());
        item.put("name", device.name());
        item.put("product_id", device.productId());
        item.put("product_name", device.productName());
        item.put("protocol", device.protocol());
        item.put("protocol_name", device.protocolEnglishName());
        item.put("protocol_version", device.protocolVersion());
        item.put("state", device.state());
        item.put("enabled", device.enabled());
        item.put("gun_count", device.gunCount());
        item.put("matched_gun_no", device.matchedGunNo());
        item.put("last_seen", device.lastSeen());
        item.put("key_metrics", device.keyMetrics());
        return item;
    }

    private static TelemetryPoint firstWithSoc(List<TelemetryPoint> points) {
        for (TelemetryPoint point : points) {
            if (point.soc() != null) {
                return point;
            }
        }
        return null;
    }

    private static TelemetryPoint lastWithSoc(List<TelemetryPoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).soc() != null) {
                return points.get(i);
            }
        }
        return null;
    }

    private static BigDecimal socValue(TelemetryPoint point) {
        return point == null ? null : point.soc();
    }

    private static Map<String, Object> envelope(Object data, boolean partial, List<String> warnings) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("data", data);
        value.put("data_time", LocalDateTime.now().toString());
        value.put("source", "OPS");
        value.put("partial", partial);
        value.put("warnings", warnings);
        return value;
    }

    private Identity requireActor(AiContext context) {
        try {
            return identities
                    .byId(Long.parseLong(context.actor()))
                    .filter(identity ->
                            identity.enabled() && identity.permissions().contains("ai:use"))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void requireAiAdmin(AiContext context) {
        if (!requireActor(context).permissions().contains("ai:admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private record DeviceTarget(
            DeviceInstance device, DeviceProtocolMapping mapping, String platformGunNo, String lookupType) {}

    private record FrameFilter(Integer frameType, String frameLabel) {}

    private static final class AiToolFailure extends RuntimeException {
        private final int status;
        private final String code;
        private final Map<String, Object> details;

        private AiToolFailure(int status, String code, String message) {
            this(status, code, message, Map.of());
        }

        private AiToolFailure(int status, String code, String message, Map<String, Object> details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details == null ? Map.of() : details;
        }
    }

    private AiContext context(String operation, Map<String, Object> args) {
        try {
            return contexts.decode(operation, args.get("_ipe_context"));
        } catch (GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Integer>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("status", ex.getStatusCode().value()));
    }

    @ExceptionHandler(AiToolFailure.class)
    ResponseEntity<Map<String, Object>> toolFailure(AiToolFailure ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.status);
        body.put("code", ex.code);
        body.put("message", ex.getMessage());
        body.putAll(ex.details);
        return ResponseEntity.status(ex.status).body(body);
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    ResponseEntity<Map<String, Integer>> invalidOperationArguments() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404));
    }

    private static String string(Map<?, ?> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid string parameter");
        }
        return value;
    }

    private static long number(Map<?, ?> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException("Invalid integer parameter");
        }
        return ((Number) value).longValue();
    }
}
