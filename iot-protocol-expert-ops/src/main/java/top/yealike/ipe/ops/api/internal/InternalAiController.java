package top.yealike.ipe.ops.api.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.service.BillService;
import top.yealike.ipe.ops.service.OrderService;
import top.yealike.ipe.ops.telemetry.OrderTelemetryService;

/**
 * AI 服务的只读业务网关。
 *
 * <p>只暴露白名单字段，不提供通用查询能力；每次调用都用 X-Ai-Station-Scope 再做一次站点范围校验， 不依赖 AI 侧过滤。服务身份由 ServiceTokenFilter 校验（/internal/** 路径）。
 */
@RestController
@RequestMapping("/internal/ai")
public class InternalAiController {

    private static final String SCOPE_HEADER = "X-Ai-Station-Scope";
    private static final String ACTOR_HEADER = "X-Ai-Actor";
    private static final java.util.regex.Pattern STORAGE_KEY = java.util.regex.Pattern.compile("[0-9a-f-]{36}");

    private final OrderService orders;
    private final BillService bills;
    private final DeviceInstanceService devices;
    private final OrderTelemetryService telemetry;
    private final JdbcTemplate jdbc;
    private final top.yealike.ipe.ops.security.IdentityService identities;
    private final String documentDirectory;

    public InternalAiController(
            OrderService orders,
            BillService bills,
            DeviceInstanceService devices,
            OrderTelemetryService telemetry,
            JdbcTemplate jdbc,
            top.yealike.ipe.ops.security.IdentityService identities,
            @Value("${ipe.ops.protocol-document-dir:./data/protocol-documents}") String documentDirectory) {
        this.orders = orders;
        this.bills = bills;
        this.devices = devices;
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.identities = identities;
        this.documentDirectory = documentDirectory;
    }

    /** Resolve an immutable user ID for queued runs and authorization checks. */
    @GetMapping("/users/{id}/identity")
    public ResponseEntity<top.yealike.ipe.ops.security.IdentityService.Identity> userIdentity(@PathVariable long id) {
        return identities
                .byId(id)
                .filter(top.yealike.ipe.ops.security.IdentityService.Identity::enabled)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> order(HttpServletRequest request, @PathVariable String orderNo) {
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(request, order.stationId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order", orders.view(order));
        data.put("bills", bills.byOrder(order.id).stream().map(bills::view).toList());
        data.put("device", deviceSummary(devices.detail(order.deviceId).device()));
        data.put("station", stationSummary(order.stationId));
        return envelope(data, false, List.of());
    }

    @GetMapping("/orders/{orderNo}/bills")
    public Map<String, Object> orderBills(HttpServletRequest request, @PathVariable String orderNo) {
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(request, order.stationId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.orderNo);
        data.put("amount_source", order.amountSource);
        data.put("bills", bills.byOrder(order.id).stream().map(this::billView).toList());
        return envelope(data, false, List.of());
    }

    @GetMapping("/devices/{id}")
    public Map<String, Object> device(HttpServletRequest request, @PathVariable Long id) {
        DeviceDtos.DeviceDetail detail = devices.detail(id);
        requireStationScope(request, detail.device().stationId());
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

    @GetMapping("/orders/{orderNo}/telemetry-summary")
    public Map<String, Object> telemetrySummary(
            HttpServletRequest request,
            @PathVariable String orderNo,
            @RequestParam(name = "window_hours", required = false) Integer windowHours) {
        ChargeOrder order = orders.require(orderNo);
        requireStationScope(request, order.stationId);
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

    @GetMapping("/documents")
    public Map<String, Object> documents(HttpServletRequest request) {
        requireAiAdmin(request);
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

    @GetMapping("/documents/{id}/content")
    public ResponseEntity<Resource> documentContent(HttpServletRequest request, @PathVariable long id) {
        requireAiAdmin(request);
        if (id <= 0) {
            return ResponseEntity.badRequest().build();
        }
        String storageKey = jdbc
                .query(
                        "select storage_key from ipe_ops.protocol_document where id = ? and status = 'ACTIVE'",
                        (rs, rowNum) -> rs.getString("storage_key"),
                        id)
                .stream()
                .findFirst()
                .orElse(null);
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Path root = Paths.get(documentDirectory).toRealPath();
            Path file = root.resolve(storageKey).toRealPath();
            if (!file.startsWith(root)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            byte[] bytes = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new ByteArrayResource(bytes));
        } catch (IOException exception) {
            return ResponseEntity.notFound().build();
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

    private void requireStationScope(HttpServletRequest request, Long stationId) {
        var identity = requireActor(request);
        if (!identity.canAccessStation(stationId)) throw new StationOutOfScopeException();
        String scope = request.getHeader(SCOPE_HEADER);
        if (scope == null || scope.isBlank()) {
            throw new StationOutOfScopeException();
        }
        if ("*".equals(scope.strip())) {
            return;
        }
        Set<String> allowed = Set.of(scope.split(","));
        if (stationId == null || !allowed.contains(String.valueOf(stationId))) {
            throw new StationOutOfScopeException();
        }
    }

    private top.yealike.ipe.ops.security.IdentityService.Identity requireActor(HttpServletRequest request) {
        try {
            long id = Long.parseLong(request.getHeader(ACTOR_HEADER));
            return identities
                    .byId(id)
                    .filter(i -> i.enabled() && i.permissions().contains("ai:use"))
                    .orElseThrow(StationOutOfScopeException::new);
        } catch (NumberFormatException exception) {
            throw new StationOutOfScopeException();
        }
    }

    private void requireAiAdmin(HttpServletRequest request) {
        if (!requireActor(request).permissions().contains("ai:admin")) throw new StationOutOfScopeException();
    }

    static final class StationOutOfScopeException extends RuntimeException {
        StationOutOfScopeException() {
            super("STATION_OUT_OF_SCOPE");
        }
    }

    @ExceptionHandler(StationOutOfScopeException.class)
    ResponseEntity<Map<String, Object>> handleScope() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "STATION_OUT_OF_SCOPE", "message", "无权访问该站点数据"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleMissing(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", "未找到对应数据"));
    }
}
