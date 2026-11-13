package top.yealike.ipe.ops.api.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.ipe.ops.config.OpsProperties;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.security.IdentityService;
import top.yealike.ipe.ops.service.BillService;
import top.yealike.ipe.ops.service.OrderService;
import top.yealike.ipe.ops.telemetry.OrderTelemetryService;

/**
 * Read-only internal HTTP endpoints mapped to tools by the gateway.
 */
@RestController
@RequestMapping(path = "/internal/ai/rest", consumes = "application/json", produces = "application/json")
public class OpsAiRestController {

    private static final int DOCUMENT_CHUNK_BYTES = 128 * 1024;
    private static final java.util.regex.Pattern STORAGE_KEY = java.util.regex.Pattern.compile("[0-9a-f-]{36}");

    private final OrderService orders;
    private final BillService bills;
    private final DeviceInstanceService devices;
    private final OrderTelemetryService telemetry;
    private final JdbcTemplate jdbc;
    private final IdentityService identities;
    private final OpsSessionLookup sessions;
    private final AiContextCodec contexts;
    private final String documentDirectory;

    public OpsAiRestController(
        OrderService orders,
        BillService bills,
        DeviceInstanceService devices,
        OrderTelemetryService telemetry,
        JdbcTemplate jdbc,
        IdentityService identities,
        OpsSessionLookup sessions,
            AiContextCodec contexts,
        @Value("${ipe.ops.protocol-document-dir:./data/protocol-documents}") String documentDirectory) {
        this.orders = orders;
        this.bills = bills;
        this.devices = devices;
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.identities = identities;
        this.sessions = sessions;
        this.contexts = contexts;
        this.documentDirectory = documentDirectory;
    }

    @PostMapping("/resolve_session")
    public Map<String, Object> session(@RequestBody Map<String, Object> args) {
        var identity = sessions.resolve(context("resolve_session", args).session());
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return identity;
    }

    /**
     * Resolve an immutable user ID for queued runs and authorization checks.
     */
    @PostMapping("/resolve_actor")
    public IdentityService.Identity userIdentity(@RequestBody Map<String, Object> args) {
        context("resolve_actor", args);
        return identities
            .byId(number(args, "id"))
            .filter(IdentityService.Identity::enabled)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/get_order_detail")
    public Map<String, Object> order(@RequestBody Map<String, Object> args) {
        var context = context("get_order_detail", args);
        String orderNo = string(args, "order_no");
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(context, order.stationId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", orders.view(order));
        data.put("bills", bills.byOrder(order.id).stream().map(bills::view).toList());
        data.put("device", deviceSummary(devices.detail(order.deviceId).device()));
        data.put("station", stationSummary(order.stationId));
        return envelope(data, false, List.of());
    }

    @PostMapping("/get_order_bills")
    public Map<String, Object> orderBills(@RequestBody Map<String, Object> args) {
        var context = context("get_order_bills", args);
        String orderNo = string(args, "order_no");
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(context, order.stationId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.orderNo);
        data.put("amount_source", order.amountSource);
        data.put("bills", bills.byOrder(order.id).stream().map(this::billView).toList());
        return envelope(data, false, List.of());
    }

    @PostMapping("/get_device_detail")
    public Map<String, Object> device(@RequestBody Map<String, Object> args) {
        var context = context("get_device_detail", args);
        long id = number(args, "device_id");
        DeviceDtos.DeviceDetail detail = devices.detail(id);
        requireStationScope(context, detail.device().stationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("device", deviceSummary(detail.device()));
        data.put("station", stationSummary(detail.device().stationId()));
        data.put(
            "channels",
            detail.channels().stream()
                .map(channel -> Map.of(
                    "channelNo", channel.channelNo(),
                    "channelType", channel.channelType(),
                    "name", channel.name() == null ? "" : channel.name()))
                .toList());
        return envelope(data, false, List.of());
    }

    @PostMapping("/get_order_telemetry_summary")
    public Map<String, Object> telemetrySummary(@RequestBody Map<String, Object> args) {
        var context = context("get_order_telemetry_summary", args);
        String orderNo = string(args, "order_no");
        Integer windowHours = args.containsKey("window_hours") ? Math.toIntExact(number(args, "window_hours")) : null;
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(context, order.stationId);
        OrderTelemetryService.Result result;
        try {
            result = telemetry.query(orderNo);
        } catch (IllegalStateException exception) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("order_no", orderNo);
            data.put("available", false);
            return envelope(data, true, List.of("订单时序数据正在同步，请稍后重试"));
        }
        List<OrderTelemetryService.Point> points = result.points();
        if (windowHours != null && windowHours > 0 && !points.isEmpty()) {
            Instant threshold = Instant.now().minusSeconds(windowHours * 3600L);
            points = points.stream()
                .filter(point -> point.time() != null && point.time().isAfter(threshold))
                .toList();
        }
        OrderTelemetryService.Point first = firstWithSoc(points);
        OrderTelemetryService.Point last = lastWithSoc(points);
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
    public Map<String, Object> documents(@RequestBody Map<String, Object> args) {
        requireAiAdmin(context("list_documents", args));
        List<Map<String, Object>> rows = jdbc.query("""
            select id::text as id, protocol_code, protocol_name_en, document_version, title, original_filename,
                   content_type, file_size, sha256, created_at
            from ipe_ops.protocol_document where status = 'ACTIVE' order by created_at desc
            """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("protocolCode", rs.getString("protocol_code"));
            row.put("protocolNameEn", rs.getString("protocol_name_en"));
            row.put("protocolVersion", rs.getString("document_version"));
            row.put("title", rs.getString("title"));
            row.put("originalFilename", rs.getString("original_filename"));
            row.put("contentType", rs.getString("content_type"));
            row.put("fileSize", rs.getLong("file_size"));
            row.put("sha256", rs.getString("sha256"));
            return row;
        });
        return envelope(rows, false, List.of());
    }

    @PostMapping("/read_document")
    public Map<String, Object> readDocument(@RequestBody Map<String, Object> args) {
        requireAiAdmin(context("read_document", args));
        long id = number(args, "id");
        long offset = number(args, "offset");
        if (id <= 0 || offset < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String storageKey = jdbc
            .query(
                "select storage_key from ipe_ops.protocol_document where id = ? and status = 'ACTIVE'",
                (rs, rowNum) -> rs.getString("storage_key"),
                id)
            .stream()
            .findFirst()
            .orElse(null);
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

    private Map<String, Object> billView(top.yealike.ipe.ops.domain.Bill bill) {
        OpsDtos.BillView view = bills.view(bill);
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

    private Map<String, Object> deviceSummary(DeviceDtos.DeviceRow device) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", device.id());
        item.put("device_code", device.deviceCode());
        item.put("name", device.name());
        item.put("device_type", device.deviceType());
        item.put("protocol", device.protocol());
        item.put("protocol_name", device.protocolName());
        item.put("protocol_version", device.protocolVersion());
        item.put("protocol_device_id", device.protocolDeviceId());
        item.put("station_id", device.stationId());
        item.put("station_name", device.stationName());
        item.put("state", device.state());
        item.put("gun_count", device.gunCount());
        item.put("last_seen", device.lastSeen());
        return item;
    }

    private Map<String, Object> stationSummary(Long stationId) {
        if (stationId == null) {
            return Map.of();
        }
        return jdbc
            .query(
                "select id, station_code, name from ipe_ops.station where id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("station_code", rs.getString("station_code"));
                    item.put("name", rs.getString("name"));
                    return item;
                },
                stationId)
            .stream()
            .findFirst()
            .orElse(Map.of());
    }

    private static OrderTelemetryService.Point firstWithSoc(List<OrderTelemetryService.Point> points) {
        for (OrderTelemetryService.Point point : points) {
            if (point.soc() != null) {
                return point;
            }
        }
        return null;
    }

    private static OrderTelemetryService.Point lastWithSoc(List<OrderTelemetryService.Point> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).soc() != null) {
                return points.get(i);
            }
        }
        return null;
    }

    private static BigDecimal socValue(OrderTelemetryService.Point point) {
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

    private void requireStationScope(RequestContext context, Long stationId) {
        var identity = requireActor(context);
        String scope = context.scope();
        if (!identity.canAccessStation(stationId)
            || (!"*".equals(scope)
            && (scope == null
            || stationId == null
            || !List.of(scope.split(",")).contains(stationId.toString())))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private IdentityService.Identity requireActor(RequestContext context) {
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

    private void requireAiAdmin(RequestContext context) {
        if (!requireActor(context).permissions().contains("ai:admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private record RequestContext(String actor, String scope, String session) {
    }

    private RequestContext context(String operation, Map<String, Object> args) {
        try {
            var context = contexts.decode(operation, args.get("_ipe_context"));
            return new RequestContext(context.actor(), context.scope(), context.session());
        } catch (GeneralSecurityException | IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Integer>> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
            .body(Map.of("status", ex.getStatusCode().value()));
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
