package top.yealike.ipe.ops.diagnostic;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.ops.config.OpsProperties;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.domain.Bill;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.domain.FrameLog;
import top.yealike.ipe.ops.domain.ProtocolDocSection;
import top.yealike.ipe.ops.domain.TroubleCase;
import top.yealike.ipe.ops.repository.ProtocolDocSectionRepository;
import top.yealike.ipe.ops.repository.TroubleCaseRepository;
import top.yealike.ipe.ops.service.BillService;
import top.yealike.ipe.ops.service.DeviceService;
import top.yealike.ipe.ops.service.FrameLogService;
import top.yealike.ipe.ops.service.OrderService;

@Service
public class DiagnosticService {

    private final DeviceService devices;
    private final OrderService orders;
    private final BillService bills;
    private final FrameLogService frames;
    private final FrameDecodeService decoder;
    private final ProtocolDocSectionRepository docs;
    private final ModelSummaryService model;
    private final TroubleCaseRepository cases;
    private final OpsProperties properties;
    private final ObjectMapper json;

    public DiagnosticService(
            DeviceService devices,
            OrderService orders,
            BillService bills,
            FrameLogService frames,
            FrameDecodeService decoder,
            ProtocolDocSectionRepository docs,
            ModelSummaryService model,
            TroubleCaseRepository cases,
            OpsProperties properties,
            ObjectMapper json) {
        this.devices = devices;
        this.orders = orders;
        this.bills = bills;
        this.frames = frames;
        this.decoder = decoder;
        this.docs = docs;
        this.model = model;
        this.cases = cases;
        this.properties = properties;
        this.json = json;
    }

    public record Request(
            Long deviceId,
            String sn,
            String protocol,
            String protocolDeviceId,
            String orderNo,
            String raw,
            String question,
            LocalDateTime from,
            LocalDateTime to,
            String operator) {}

    public record Step(String step, String status, LocalDateTime time, String evidence, String detail) {}

    public record Citation(String locator, String heading, String content) {}

    public record Result(
            boolean ok,
            String error,
            Object device,
            Object decoded,
            Object order,
            List<Object> bills,
            List<Step> timeline,
            List<Citation> citations,
            List<String> pending,
            String lastEvidencedStep,
            String conclusion,
            boolean modelGenerated,
            String modelSummary,
            Map<String, Object> capabilities) {}

    @Transactional
    public Result diagnose(Request request) {
        DeviceInstance device = resolveDevice(request);
        if (device == null) {
            return new Result(
                    false,
                    "未找到设备：请提供平台设备 ID、SN 或 协议+协议侧编号",
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    "无法开始排障：设备未定位。",
                    false,
                    null,
                    capabilities(false));
        }
        Object decoded = request.raw() == null || request.raw().isBlank()
                ? null
                : decoder.decode(request.raw(), device.protocol);

        LocalDateTime from = request.from() == null ? LocalDateTime.now().minusDays(7) : request.from();
        LocalDateTime to = request.to() == null ? LocalDateTime.now().plusMinutes(1) : request.to();
        List<FrameLog> frameLogs = frames.search(device.id, null, null, null, from, to, 500);
        ChargeOrder order = orderOf(request, device);
        List<Bill> orderBills = order == null ? List.of() : bills.byOrder(order.id);
        String version = "1.6";

        List<Step> timeline = new ArrayList<>();
        timeline.add(new Step(
                "设备定位",
                "CONFIRMED",
                null,
                "device:" + device.id,
                device.deviceCode + " / " + device.protocol + " / " + device.protocolDeviceId));
        timeline.add(new Step("协议判定", "CONFIRMED", null, "device:" + device.id, device.protocol));
        if (decoded != null) {
            Map<?, ?> decodedMap = decoded instanceof Map<?, ?> map ? map : Map.of();
            boolean parsed = Boolean.TRUE.equals(decodedMap.get("ok"));
            timeline.add(new Step(
                    "报文释义",
                    parsed ? "CONFIRMED" : "MISSING",
                    null,
                    parsed ? "input:raw" : null,
                    parsed ? "结构与字段已解析" : String.valueOf(decodedMap.get("error"))));
        }
        if (order == null) {
            timeline.add(new Step("订单定位", "MISSING", null, null, "时间窗内没有找到订单"));
        } else {
            timeline.add(new Step(
                    "订单创建",
                    "CONFIRMED",
                    order.createdAt,
                    "order:" + order.orderNo,
                    order.status.name() + " / " + order.orderNo));
            timeline.add(new Step(
                    "开始充电",
                    order.startTime != null ? "CONFIRMED" : "MISSING",
                    order.startTime,
                    order.startTime != null ? "order:" + order.orderNo : null,
                    order.startTime == null ? "订单没有开始时间" : "设备开始供电"));
            timeline.add(new Step(
                    "结束充电",
                    order.endTime != null ? "CONFIRMED" : "MISSING",
                    order.endTime,
                    order.endTime != null ? "order:" + order.orderNo : null,
                    order.endTime == null ? "订单尚未结束" : "停止原因 " + order.stopReason));
            boolean settled = order.status == ChargeOrder.Status.SETTLED;
            timeline.add(new Step(
                    "订单结算",
                    settled ? "CONFIRMED" : "MISSING",
                    order.updatedAt,
                    settled ? "order:" + order.orderNo : null,
                    settled ? "金额来源 " + order.amountSource : "当前状态 " + order.status));
            timeline.add(new Step(
                    "账单入库",
                    orderBills.isEmpty() ? "MISSING" : "CONFIRMED",
                    orderBills.isEmpty() ? null : orderBills.get(0).receivedAt,
                    orderBills.isEmpty() ? null : "bill:" + orderBills.get(0).id,
                    orderBills.isEmpty() ? "关联订单没有账单记录" : orderBills.get(0).amountType));
        }

        addProtocolSteps(timeline, device.protocol, frameLogs);
        boolean lokiAvailable =
                properties.getLokiUrl() != null && !properties.getLokiUrl().isBlank();
        timeline.add(new Step(
                "集中日志查询",
                lokiAvailable ? "CONFIRMED" : "UNKNOWN",
                null,
                null,
                lokiAvailable ? "Loki 已配置" : "未配置 Loki 查询端点，仅使用业务帧流水"));

        List<String> pending = new ArrayList<>();
        if (order == null) {
            pending.add("未找到订单，需确认流水号或时间范围。");
        } else {
            if (order.status == ChargeOrder.Status.PENDING) pending.add("订单仍处于待启动，未收到有效的启动完成信号。");
            if (order.status == ChargeOrder.Status.STOPPED && orderBills.isEmpty()) {
                pending.add("停充后账单缺失：订单已停止但未查到账单入库记录。");
            }
            if (order.status == ChargeOrder.Status.SETTLED && orderBills.isEmpty()) {
                pending.add("订单已结算但账单表没有记录，需检查账单写入事务。");
            }
        }
        if (device.protocol.equals("YKC16")) {
            if (hasFrame(frameLogs, "IN", 0x3B)) {
                // 有交易记录
            } else {
                pending.add("未找到云快充 0x3B 交易记录上报。");
            }
            if (!hasFrame(frameLogs, "OUT", 0x40)) {
                pending.add("未找到 0x40 交易记录确认下发。");
            }
        } else if (device.protocol.equals("OCPP16J")) {
            if (frameLogs.stream().noneMatch(f -> "IN".equals(f.direction) && "StopTransaction".equals(f.frameLabel))) {
                pending.add("未找到 OCPP StopTransaction 上报。");
            }
        }
        if (!lokiAvailable) {
            pending.add("日志证据不足：未接入 Loki，无法核对通道运行日志。");
        }

        List<Citation> citations = citations(device.protocol, version, request.question(), order);
        String last = null;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            Step step = timeline.get(i);
            if ("CONFIRMED".equals(step.status())) {
                last = step.step();
                break;
            }
        }
        String conclusion = conclusion(last, pending, order, orderBills);
        String summary = null;
        boolean modelGenerated = false;
        if (properties.getModel().isEnabled()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put(
                    "device",
                    Map.of(
                            "sn",
                            device.deviceCode,
                            "protocol",
                            device.protocol,
                            "protocolDeviceId",
                            device.protocolDeviceId,
                            "online",
                            device.isOnline()));
            facts.put("decoded", decoded);
            facts.put(
                    "order",
                    order == null
                            ? null
                            : Map.of(
                                    "orderNo",
                                    order.orderNo,
                                    "status",
                                    order.status.name(),
                                    "energy",
                                    order.energy,
                                    "amount",
                                    order.amount,
                                    "amountSource",
                                    order.amountSource));
            facts.put("bills", orderBills.size());
            facts.put("timeline", timeline);
            facts.put("pending", pending);
            Optional<String> generated = model.summarize(request.question(), facts);
            if (generated.isPresent()) {
                summary = generated.get();
                modelGenerated = true;
            } else {
                pending.add("模型端点不可用，已返回确定性解析与证据时间线。");
            }
        }

        TroubleCase saved = saveCase(request, device, order, timeline, conclusion);
        return new Result(
                true,
                null,
                devices.view(device),
                decoded,
                order == null ? null : orders.view(order),
                orderBills.stream().map(bills::view).map(o -> (Object) o).toList(),
                timeline,
                citations,
                pending,
                last,
                conclusion,
                modelGenerated,
                summary,
                capabilities(lokiAvailable));
    }

    public List<TroubleCase> recent() {
        return cases.findTop50ByOrderByIdDesc();
    }

    private DeviceInstance resolveDevice(Request request) {
        if (request.orderNo() != null && !request.orderNo().isBlank()) {
            ChargeOrder order = orders.findByOrderNo(request.orderNo());
            if (order != null) {
                try {
                    return devices.get(order.deviceId);
                } catch (RuntimeException ignored) {
                }
            }
        }
        if (request.deviceId() != null) {
            try {
                return devices.get(request.deviceId());
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (request.sn() != null && !request.sn().isBlank()) {
            List<DeviceInstance> matches = devices.searchByCode(request.sn());
            return matches.size() == 1 ? matches.get(0) : null;
        }
        if (request.protocol() != null && request.protocolDeviceId() != null) {
            try {
                return devices.resolve(request.protocol(), request.protocolDeviceId());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private ChargeOrder orderOf(Request request, DeviceInstance device) {
        if (request.orderNo() != null && !request.orderNo().isBlank()) {
            return orders.findByOrderNo(request.orderNo());
        }
        return orders.recent().stream()
                .filter(o -> Objects.equals(o.deviceId, device.id))
                .findFirst()
                .orElseGet(() -> orders.active(device.id, "01"));
    }

    private void addProtocolSteps(List<Step> timeline, String protocol, List<FrameLog> logs) {
        if ("YKC16".equals(protocol)) {
            FrameLog tx = lastFrame(logs, "IN", 0x3B);
            timeline.add(new Step(
                    "交易记录 0x3B 上报",
                    tx == null ? "MISSING" : "CONFIRMED",
                    tx == null ? null : tx.createdAt,
                    tx == null ? null : "frame:" + tx.id,
                    tx == null ? "未查到 0x3B" : tx.summary));
            FrameLog ack = lastFrame(logs, "OUT", 0x40);
            timeline.add(new Step(
                    "交易记录确认 0x40",
                    ack == null ? "MISSING" : "CONFIRMED",
                    ack == null ? null : ack.createdAt,
                    ack == null ? null : "frame:" + ack.id,
                    ack == null ? "未查到 0x40" : ack.summary));
        } else if ("OCPP16J".equals(protocol)) {
            FrameLog stop = logs.stream()
                    .filter(f -> "IN".equals(f.direction) && "StopTransaction".equals(f.frameLabel))
                    .findFirst()
                    .orElse(null);
            timeline.add(new Step(
                    "StopTransaction 上报",
                    stop == null ? "MISSING" : "CONFIRMED",
                    stop == null ? null : stop.createdAt,
                    stop == null ? null : "frame:" + stop.id,
                    stop == null ? "未查到 StopTransaction" : stop.summary));
            FrameLog ack = stop == null
                    ? null
                    : logs.stream()
                            .filter(f -> "OUT".equals(f.direction) && Objects.equals(f.messageId, stop.messageId))
                            .findFirst()
                            .orElse(null);
            timeline.add(new Step(
                    "StopTransaction 应答",
                    ack == null ? "MISSING" : "CONFIRMED",
                    ack == null ? null : ack.createdAt,
                    ack == null ? null : "frame:" + ack.id,
                    ack == null ? "未找到同一消息 ID 的应答" : ack.summary));
        }
    }

    private static boolean hasFrame(List<FrameLog> logs, String direction, int type) {
        return logs.stream().anyMatch(f -> direction.equals(f.direction) && f.frameType == type);
    }

    private static FrameLog lastFrame(List<FrameLog> logs, String direction, int type) {
        return logs.stream()
                .filter(f -> direction.equals(f.direction) && f.frameType == type)
                .findFirst()
                .orElse(null);
    }

    private List<Citation> citations(String protocol, String version, String question, ChargeOrder order) {
        Set<ProtocolDocSection> found = new LinkedHashSet<>();
        String frame = "OCPP16J".equals(protocol) ? "StopTransaction" : "0x3B";
        found.addAll(docs.findByFrame(protocol, version, frame));
        if ("YKC16".equals(protocol)) {
            found.addAll(docs.findByFrame(protocol, version, "0x40"));
        }
        if (question != null && !question.isBlank()) {
            found.addAll(docs.search(protocol, version, question));
        }
        return found.stream()
                .limit(6)
                .map(s -> new Citation(s.locator, s.heading, s.content))
                .toList();
    }

    private static String conclusion(String last, List<String> pending, ChargeOrder order, List<Bill> bills) {
        StringBuilder text = new StringBuilder();
        if (last != null) {
            text.append("最后一个有证据的处理环节：").append(last).append("。");
        } else {
            text.append("没有形成可用的处理时间线。");
        }
        if (order != null) {
            text.append(" 订单 ")
                    .append(order.orderNo)
                    .append(" 当前状态 ")
                    .append(order.status.name())
                    .append("，电量 ")
                    .append(order.energy)
                    .append(" kWh，金额 ")
                    .append(order.amount)
                    .append(" 元（")
                    .append(order.amountSource)
                    .append("）。");
            if (bills.isEmpty()
                    && (order.status == ChargeOrder.Status.STOPPED || order.status == ChargeOrder.Status.SETTLED)) {
                text.append(" 账单记录缺失，需继续核对协议上报与账单写入事务。");
            }
        }
        if (!pending.isEmpty()) {
            text.append(" 待确认项 ").append(pending.size()).append(" 项。");
        }
        return text.toString();
    }

    private Map<String, Object> capabilities(boolean loki) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("frameLog", true);
        caps.put("loki", loki);
        caps.put("model", properties.getModel().isEnabled());
        caps.put("windowDays", 7);
        return caps;
    }

    private TroubleCase saveCase(
            Request request, DeviceInstance device, ChargeOrder order, List<Step> timeline, String conclusion) {
        TroubleCase item = new TroubleCase();
        item.deviceId = device.id;
        item.orderNo = order == null ? null : order.orderNo;
        item.protocol = device.protocol;
        item.question = request.question();
        item.createdBy = request.operator();
        item.createdAt = LocalDateTime.now();
        try {
            item.resultJson = json.writeValueAsString(Map.of("timeline", timeline, "conclusion", conclusion));
        } catch (RuntimeException e) {
            item.resultJson = conclusion;
        }
        return cases.save(item);
    }
}
