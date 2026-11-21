package top.yealike.pep.gateway.netty;

import io.micrometer.tracing.Tracer;
import io.netty.channel.Channel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.InboundFrame;
import top.yealike.pep.codec.frames.BmsFrames;
import top.yealike.pep.codec.frames.MaintenanceFrames;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.codec.frames.ParallelFrames;
import top.yealike.pep.codec.frames.RealtimeFrames;
import top.yealike.pep.codec.frames.RegistrationFrames;
import top.yealike.pep.gateway.core.DeviceIdentityRegistry;
import top.yealike.pep.gateway.core.ProtocolType;
import top.yealike.pep.gateway.ops.OpsClient;
import top.yealike.pep.gateway.ops.OpsDtos;
import top.yealike.pep.observability.protocol.ProtocolTelemetry;

/** 上行帧入口：校验与解码后，把业务判定提交运维平台，再按结果应答设备。 */
@Component
public class ProtocolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDispatcher.class);

    private final ProtocolTelemetry telemetry;
    private final SessionRegistry sessions;
    private final OpsClient ops;
    private final Tracer tracer;
    private final DeviceIdentityRegistry identities;

    public ProtocolDispatcher(
            ProtocolTelemetry telemetry,
            SessionRegistry sessions,
            OpsClient ops,
            Tracer tracer,
            DeviceIdentityRegistry identities) {
        this.telemetry = telemetry;
        this.sessions = sessions;
        this.ops = ops;
        this.tracer = tracer;
        this.identities = identities;
    }

    public void onLogin(Channel channel, InboundFrame inbound) {
        Frame frame = inbound.frame();
        RegistrationFrames.Login login = RegistrationFrames.decodeLogin(frame.body());
        String sessionId = channel.id().asLongText();
        OpsDtos.DeviceView device = null;
        DeviceIdentityRegistry.Device identity = null;
        try {
            identity = identities.resolveExternal(ProtocolType.YKC16, login.pileCode());
            device = ops.login(new OpsDtos.LoginRequest(
                    "YKC16",
                    identity.deviceSn(),
                    login.pileCode(),
                    login.pileType(),
                    login.gunCount(),
                    login.protocolVersion(),
                    login.programVersion(),
                    login.networkType(),
                    login.simCard(),
                    login.operator(),
                    null,
                    null,
                    null,
                    null,
                    0,
                    sessionId));
            sessions.bind(identity.deviceSn(), login.pileCode(), channel, frame.encrypted());
            logFrame("IN", frame, login.pileCode(), inbound.wireHex(), null);
            sessions.send(
                    identity.deviceSn(),
                    FrameType.LOGIN_ACK,
                    RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), true)),
                    frame.sequence());
            log.info(
                    "设备 {}（协议设备号 {}）登录成功（枪数 {}，平台设备 {}）",
                    identity.deviceSn(),
                    login.pileCode(),
                    device == null ? login.gunCount() : device.gunCount(),
                    device == null ? "-" : device.id());
        } catch (RuntimeException e) {
            telemetry.failure(e);
            logFrame("IN", frame, login.pileCode(), inbound.wireHex(), e.getMessage());
            try {
                sessions.sendUnauthenticated(
                        channel,
                        login.pileCode(),
                        frame.encrypted(),
                        FrameType.LOGIN_ACK,
                        RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), false)),
                        frame.sequence());
            } catch (RuntimeException ignored) {
            }
            log.warn(
                    "协议设备号 {} 登录被拒绝{}: {}",
                    login.pileCode(),
                    identity == null ? "" : "（平台 SN " + identity.deviceSn() + "）",
                    e.getMessage());
            channel.close();
        }
    }

    public void dispatch(String deviceSn, String externalPileCode, InboundFrame inbound) {
        Frame frame = inbound.frame();
        FrameType type = FrameType.ofOrNull(frame.frameType());
        logFrame("IN", frame, externalPileCode, inbound.wireHex(), null);
        DeviceIdentityRegistry.Device identity;
        try {
            identity = identities.resolvePlatform(ProtocolType.YKC16, deviceSn);
        } catch (RuntimeException e) {
            telemetry.failure(e);
            log.warn("设备 {} 身份已失效，关闭连接: {}", deviceSn, e.getMessage());
            return;
        }
        if (type == null) {
            telemetry.failure(new IllegalArgumentException("unknown frame type"));
            log.warn("设备 {} 上送未知帧类型 0x{}", deviceSn, String.format("%02X", frame.frameType()));
            return;
        }
        try {
            if (type.direction() != FrameType.Direction.UP) {
                return;
            }
            Object message = top.yealike.pep.codec.FramePayloads.decode(type, frame.body());
            try {
                Object address = message.getClass().getMethod("pileCode").invoke(message);
                if (!externalPileCode.equals(address)) {
                    throw new IllegalArgumentException("帧桩号与登录会话不符");
                }
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("无法解析帧桩号", e);
            }
            sessions.complete(deviceSn, frame);
            switch (type) {
                case HEARTBEAT -> {
                    RegistrationFrames.Heartbeat hb = RegistrationFrames.decodeHeartbeat(frame.body());
                    ops.touch("YKC16", deviceSn);
                    sessions.send(
                            deviceSn,
                            FrameType.HEARTBEAT_ACK,
                            RegistrationFrames.encodeHeartbeatAck(
                                    new RegistrationFrames.HeartbeatAck(externalPileCode, hb.gunNo())),
                            frame.sequence());
                }
                case RATE_VERIFY -> {
                    RegistrationFrames.RateVerify verify = RegistrationFrames.decodeRateVerify(frame.body());
                    boolean consistent = ops.verifyRateModel(verify.modelNo());
                    sessions.send(
                            deviceSn,
                            FrameType.RATE_VERIFY_ACK,
                            RegistrationFrames.encodeRateVerifyAck(new RegistrationFrames.RateVerifyAck(
                                    externalPileCode, verify.modelNo(), consistent)),
                            frame.sequence());
                }
                case RATE_REQUEST -> {
                    OpsDtos.RateModelView model = ops.activeRateModel();
                    sessions.send(
                            deviceSn,
                            FrameType.RATE_RESPONSE,
                            RegistrationFrames.encodeRateModel(toProtocol(model, externalPileCode)),
                            frame.sequence());
                }
                case REALTIME -> {
                    RealtimeFrames.Realtime realtime = RealtimeFrames.decodeRealtime(frame.body());
                    DeviceIdentityRegistry.Connector connector = identity.connectorByExternalNo(realtime.gunNo());
                    ops.touch("YKC16", deviceSn);
                    ops.gunState(new OpsDtos.RealtimeRequest(
                            "YKC16",
                            deviceSn,
                            connector.gunNo(),
                            realtime.state(),
                            realtime.gunHomed(),
                            realtime.plugged(),
                            realtime.outputVoltage(),
                            realtime.outputCurrent(),
                            realtime.gunLineTemp(),
                            realtime.soc(),
                            realtime.batteryMaxTemp(),
                            realtime.totalChargeMinutes(),
                            realtime.remainMinutes(),
                            realtime.chargedEnergy(),
                            realtime.chargedAmount(),
                            realtime.hardwareFault(),
                            realtime.transactionNo()));
                }
                case START_REQUEST -> {
                    OperationFrames.StartRequest request = OperationFrames.decodeStartRequest(frame.body());
                    DeviceIdentityRegistry.Connector connector = identity.connectorByExternalNo(request.gunNo());
                    OpsDtos.StartResult result = ops.start(new OpsDtos.StartRequest(
                            "YKC16",
                            deviceSn,
                            connector.gunNo(),
                            request.startType(),
                            request.account(),
                            "00000000",
                            request.account()));
                    OperationFrames.StartAck ack = new OperationFrames.StartAck(
                            result.accepted() ? result.transactionNo() : "0".repeat(32),
                            externalPileCode,
                            request.gunNo(),
                            "00000000",
                            result.accepted() ? new BigDecimal("1000.00") : BigDecimal.ZERO,
                            result.accepted(),
                            result.failReason() == null ? 0x05 : result.failReason());
                    sessions.send(deviceSn, FrameType.START_ACK, OperationFrames.encodeStartAck(ack), frame.sequence());
                    if (result.accepted()) {
                        ops.chargingByTransaction("YKC16", deviceSn, result.transactionNo(), LocalDateTime.now());
                    }
                }
                case REMOTE_START_ACK -> {
                    OperationFrames.RemoteStartAck ack = OperationFrames.decodeRemoteStartAck(frame.body());
                    if (ack.success()) {
                        ops.chargingByTransaction("YKC16", deviceSn, ack.transactionNo(), LocalDateTime.now());
                    } else if (ack.failReason() != 5) {
                        ops.failByTransaction("YKC16", deviceSn, ack.transactionNo(), "桩拒绝启动: " + ack.failReason());
                    }
                }
                case PARALLEL_START_REQUEST -> {
                    ParallelFrames.ParallelStartRequest request =
                            ParallelFrames.decodeParallelStartRequest(frame.body());
                    DeviceIdentityRegistry.Connector connector = identity.connectorByExternalNo(request.gunNo());
                    OpsDtos.StartResult result = ops.start(new OpsDtos.StartRequest(
                            "YKC16",
                            deviceSn,
                            connector.gunNo(),
                            request.startType(),
                            request.account(),
                            "00000000",
                            request.account()));
                    ParallelFrames.ParallelStartAck ack = new ParallelFrames.ParallelStartAck(
                            result.accepted() ? result.transactionNo() : "0".repeat(32),
                            externalPileCode,
                            request.gunNo(),
                            "00000000",
                            result.accepted() ? new BigDecimal("1000.00") : BigDecimal.ZERO,
                            result.accepted(),
                            result.failReason() == null ? 0x05 : result.failReason(),
                            request.parallelNo());
                    sessions.send(
                            deviceSn,
                            FrameType.PARALLEL_START_ACK,
                            ParallelFrames.encodeParallelStartAck(ack),
                            frame.sequence());
                    if (result.accepted()) {
                        ops.chargingByTransaction("YKC16", deviceSn, result.transactionNo(), LocalDateTime.now());
                    }
                }
                case PARALLEL_START_RESULT -> {
                    ParallelFrames.ParallelStartResult result = ParallelFrames.decodeParallelStartResult(frame.body());
                    if (result.success()) {
                        ops.chargingByTransaction("YKC16", deviceSn, result.transactionNo(), LocalDateTime.now());
                    } else {
                        ops.failByTransaction(
                                "YKC16", deviceSn, result.transactionNo(), "桩拒绝并充启动: " + result.failReason());
                    }
                }
                case STOP_ACK -> {
                    OperationFrames.StopAck ack = OperationFrames.decodeStopAck(frame.body());
                    if (ack.success()) {
                        DeviceIdentityRegistry.Connector connector = identity.connectorByExternalNo(ack.gunNo());
                        OpsDtos.OrderView order = ops.active("YKC16", deviceSn, connector.gunNo());
                        if (order != null) {
                            ops.stop(order.orderNo(), 0x40);
                        }
                    }
                }
                case TRANSACTION -> {
                    OperationFrames.Transaction tx = OperationFrames.decodeTransaction(frame.body());
                    DeviceIdentityRegistry.Connector connector = identity.connectorByExternalNo(tx.gunNo());
                    List<OpsDtos.YkcEnergySegment> segments = java.util.stream.Stream.of(
                                    tx.sharp(), tx.peak(), tx.flat(), tx.valley())
                            .filter(java.util.Objects::nonNull)
                            .map(segment -> new OpsDtos.YkcEnergySegment(
                                    segment.unitPrice(), segment.energy(), segment.lossEnergy(), segment.amount()))
                            .toList();
                    OpsDtos.BillResult bill = ops.acceptYkc(new OpsDtos.BillRequest(
                            "YKC16",
                            deviceSn,
                            tx.transactionNo(),
                            connector.gunNo(),
                            tx.startTime(),
                            tx.endTime(),
                            tx.tradeTime(),
                            tx.totalEnergy(),
                            tx.totalAmount(),
                            tx.lossTotalEnergy(),
                            segments,
                            tx.stopReason(),
                            tx.tradeFlag(),
                            tx.physicalCard(),
                            tx.vin(),
                            inbound.wireHex()));
                    sessions.send(
                            deviceSn,
                            FrameType.TRANSACTION_ACK,
                            OperationFrames.encodeTransactionAck(
                                    new OperationFrames.TransactionAck(tx.transactionNo(), bill.accepted())),
                            frame.sequence());
                }
                case TIME_SYNC_ACK -> log.debug("设备 {} 对时完成", deviceSn);
                case PARAM_SET_ACK,
                        REBOOT_ACK,
                        UPDATE_ACK,
                        RATE_SET_ACK,
                        BALANCE_ACK,
                        CARD_SYNC_ACK,
                        CARD_CLEAR_ACK,
                        CARD_QUERY_ACK,
                        LOCK_CONTROL_ACK -> log.debug("设备 {} 应答 {}", deviceSn, type.label());
                case CHARGE_HANDSHAKE,
                        PARAM_CONFIG,
                        CHARGE_END,
                        ERROR_REPORT,
                        BMS_ABORT,
                        CHARGER_ABORT,
                        BMS_DEMAND,
                        BMS_INFO -> {
                    BmsFrames.Message bms = BmsFrames.decode(type, frame.body());
                    log.debug("设备 {} {} 流水={} 字段={}", deviceSn, type.label(), bms.transactionNo(), bms.values());
                }
                case LOCK_REPORT ->
                    log.info("设备 {} 地锁上报 {}", deviceSn, MaintenanceFrames.decodeLockReport(frame.body()));
                case BALANCE_UPDATE,
                        CARD_SYNC,
                        CARD_CLEAR,
                        CARD_QUERY,
                        LOCK_CONTROL,
                        REBOOT,
                        UPDATE,
                        PARAM_SET,
                        TIME_SYNC,
                        RATE_SET,
                        READ_REALTIME -> log.debug("设备 {} 上送了平台下行帧 {}，忽略", deviceSn, type.label());
                default -> log.debug("设备 {} 帧 {} 未实现业务处理", deviceSn, type.label());
            }
        } catch (RuntimeException e) {
            telemetry.failure(e);
            log.error("处理设备 {} 帧 {} 失败: {}", deviceSn, type.label(), e.getMessage(), e);
            logFrame("IN", frame, externalPileCode, inbound.wireHex(), e.getMessage());
        }
    }

    private void logFrame(String direction, Frame frame, String pileCode, String wireHex, String error) {
        FrameType type = FrameType.ofOrNull(frame.frameType());
        String traceId = null;
        var span = tracer.currentSpan();
        if (span != null && span.context() != null) {
            traceId = span.context().traceId();
        }
        ops.frame(new OpsDtos.FrameRequest(
                "YKC16",
                pileCode,
                direction,
                frame.frameType(),
                type == null ? "未知帧" : type.label(),
                null,
                frame.sequence(),
                frame.encrypted(),
                frame.bodyHex(),
                wireHex,
                FrameSummary.of(type, frame.body()),
                error,
                traceId,
                LocalDateTime.now()));
    }

    private static RegistrationFrames.RateModel toProtocol(OpsDtos.RateModelView model, String pileCode) {
        if (model == null) {
            throw new IllegalStateException("运维平台未返回计费模型");
        }
        return new RegistrationFrames.RateModel(
                pileCode,
                model.modelNo(),
                model.sharpEnergy(),
                model.sharpService(),
                model.peakEnergy(),
                model.peakService(),
                model.flatEnergy(),
                model.flatService(),
                model.valleyEnergy(),
                model.valleyService(),
                model.lossRatio(),
                model.segments());
    }
}
