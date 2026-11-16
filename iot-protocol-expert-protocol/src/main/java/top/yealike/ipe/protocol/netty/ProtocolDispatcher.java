package top.yealike.ipe.protocol.netty;

import io.micrometer.tracing.Tracer;
import io.netty.channel.Channel;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.InboundFrame;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.ops.OpsClient;
import top.yealike.ipe.protocol.ops.OpsDtos;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;

/** 上行帧入口：校验与解码后，把业务判定提交运维平台，再按结果应答设备。 */
@Component
public class ProtocolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDispatcher.class);

    private final ProtocolTelemetry telemetry;
    private final SessionRegistry sessions;
    private final OpsClient ops;
    private final Tracer tracer;

    public ProtocolDispatcher(ProtocolTelemetry telemetry, SessionRegistry sessions, OpsClient ops, Tracer tracer) {
        this.telemetry = telemetry;
        this.sessions = sessions;
        this.ops = ops;
        this.tracer = tracer;
    }

    public void onLogin(Channel channel, InboundFrame inbound) {
        Frame frame = inbound.frame();
        RegistrationFrames.Login login = RegistrationFrames.decodeLogin(frame.body());
        String sessionId = channel.id().asLongText();
        OpsDtos.DeviceView device = null;
        try {
            device = ops.login(new OpsDtos.LoginRequest(
                    "YKC16",
                    login.pileCode(),
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
            sessions.bind(login.pileCode(), channel, frame.encrypted());
            logFrame("IN", frame, login.pileCode(), inbound.wireHex(), null);
            sessions.send(
                    login.pileCode(),
                    FrameType.LOGIN_ACK,
                    RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), true)),
                    frame.sequence());
            log.info(
                    "桩 {} 登录成功（枪数 {}，平台设备 {}）",
                    login.pileCode(),
                    device == null ? login.gunCount() : device.gunCount(),
                    device == null ? "-" : device.id());
        } catch (RuntimeException e) {
            telemetry.failure(e);
            sessions.bind(login.pileCode(), channel, frame.encrypted());
            logFrame("IN", frame, login.pileCode(), inbound.wireHex(), e.getMessage());
            try {
                sessions.send(
                        login.pileCode(),
                        FrameType.LOGIN_ACK,
                        RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), false)),
                        frame.sequence());
            } catch (RuntimeException ignored) {
            }
            log.warn("桩 {} 登录被拒绝: {}", login.pileCode(), e.getMessage());
            channel.close();
        }
    }

    public void dispatch(String pileCode, InboundFrame inbound) {
        Frame frame = inbound.frame();
        FrameType type = FrameType.ofOrNull(frame.frameType());
        logFrame("IN", frame, pileCode, inbound.wireHex(), null);
        if (type == null) {
            telemetry.failure(new IllegalArgumentException("unknown frame type"));
            log.warn("桩 {} 上送未知帧类型 0x{}", pileCode, String.format("%02X", frame.frameType()));
            return;
        }
        try {
            if (type.direction() != FrameType.Direction.UP) {
                return;
            }
            Object message = top.yealike.ipe.protocol.codec.FramePayloads.decode(type, frame.body());
            try {
                Object address = message.getClass().getMethod("pileCode").invoke(message);
                if (!pileCode.equals(address)) {
                    throw new IllegalArgumentException("帧桩号与登录会话不符");
                }
            } catch (NoSuchMethodException ignored) {
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("无法解析帧桩号", e);
            }
            sessions.complete(pileCode, frame);
            switch (type) {
                case HEARTBEAT -> {
                    RegistrationFrames.Heartbeat hb = RegistrationFrames.decodeHeartbeat(frame.body());
                    ops.touch("YKC16", pileCode);
                    sessions.send(
                            pileCode,
                            FrameType.HEARTBEAT_ACK,
                            RegistrationFrames.encodeHeartbeatAck(
                                    new RegistrationFrames.HeartbeatAck(pileCode, hb.gunNo())),
                            frame.sequence());
                }
                case RATE_VERIFY -> {
                    RegistrationFrames.RateVerify verify = RegistrationFrames.decodeRateVerify(frame.body());
                    boolean consistent = ops.verifyRateModel(verify.modelNo());
                    sessions.send(
                            pileCode,
                            FrameType.RATE_VERIFY_ACK,
                            RegistrationFrames.encodeRateVerifyAck(
                                    new RegistrationFrames.RateVerifyAck(pileCode, verify.modelNo(), consistent)),
                            frame.sequence());
                }
                case RATE_REQUEST -> {
                    OpsDtos.RateModelView model = ops.activeRateModel();
                    sessions.send(
                            pileCode,
                            FrameType.RATE_RESPONSE,
                            RegistrationFrames.encodeRateModel(toProtocol(model, pileCode)),
                            frame.sequence());
                }
                case REALTIME -> {
                    RealtimeFrames.Realtime realtime = RealtimeFrames.decodeRealtime(frame.body());
                    ops.touch("YKC16", pileCode);
                    ops.gunState(new OpsDtos.RealtimeRequest(
                            "YKC16",
                            pileCode,
                            realtime.gunNo(),
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
                    OpsDtos.StartResult result = ops.start(new OpsDtos.StartRequest(
                            "YKC16",
                            pileCode,
                            request.gunNo(),
                            request.startType(),
                            request.account(),
                            "00000000",
                            request.account()));
                    OperationFrames.StartAck ack = new OperationFrames.StartAck(
                            result.accepted() ? result.orderNo() : "0".repeat(32),
                            pileCode,
                            request.gunNo(),
                            "00000000",
                            result.accepted() ? new BigDecimal("1000.00") : BigDecimal.ZERO,
                            result.accepted(),
                            result.failReason() == null ? 0x05 : result.failReason());
                    sessions.send(pileCode, FrameType.START_ACK, OperationFrames.encodeStartAck(ack), frame.sequence());
                }
                case REMOTE_START_ACK -> {
                    OperationFrames.RemoteStartAck ack = OperationFrames.decodeRemoteStartAck(frame.body());
                    if (ack.success()) {
                        ops.charging(ack.transactionNo(), LocalDateTime.now());
                    } else if (ack.failReason() != 5) {
                        ops.fail(ack.transactionNo(), "桩拒绝启动: " + ack.failReason());
                    }
                }
                case STOP_ACK -> {
                    OperationFrames.StopAck ack = OperationFrames.decodeStopAck(frame.body());
                    if (ack.success()) {
                        OpsDtos.OrderView order = ops.active("YKC16", pileCode, ack.gunNo());
                        if (order != null) {
                            ops.stop(order.orderNo(), 0x40);
                        }
                    }
                }
                case TRANSACTION -> {
                    OperationFrames.Transaction tx = OperationFrames.decodeTransaction(frame.body());
                    OpsDtos.BillResult bill = ops.acceptYkc(new OpsDtos.BillRequest(
                            "YKC16",
                            pileCode,
                            tx.transactionNo(),
                            tx.gunNo(),
                            tx.startTime(),
                            tx.endTime(),
                            tx.tradeTime(),
                            tx.totalEnergy(),
                            tx.totalAmount(),
                            tx.lossTotalEnergy(),
                            tx.stopReason(),
                            tx.tradeFlag(),
                            tx.physicalCard(),
                            tx.vin(),
                            inbound.wireHex()));
                    sessions.send(
                            pileCode,
                            FrameType.TRANSACTION_ACK,
                            OperationFrames.encodeTransactionAck(
                                    new OperationFrames.TransactionAck(tx.transactionNo(), bill.accepted())),
                            frame.sequence());
                }
                case TIME_SYNC_ACK -> log.debug("桩 {} 对时完成", pileCode);
                case PARAM_SET_ACK,
                        REBOOT_ACK,
                        UPDATE_ACK,
                        RATE_SET_ACK,
                        BALANCE_ACK,
                        CARD_SYNC_ACK,
                        CARD_CLEAR_ACK,
                        CARD_QUERY_ACK,
                        LOCK_CONTROL_ACK -> log.debug("桩 {} 应答 {}", pileCode, type.label());
                case CHARGE_HANDSHAKE,
                        PARAM_CONFIG,
                        CHARGE_END,
                        ERROR_REPORT,
                        BMS_ABORT,
                        CHARGER_ABORT,
                        BMS_DEMAND,
                        BMS_INFO -> {
                    BmsFrames.Message bms = BmsFrames.decode(type, frame.body());
                    log.debug("桩 {} {} 流水={} 字段={}", pileCode, type.label(), bms.transactionNo(), bms.values());
                }
                case LOCK_REPORT ->
                    log.info("桩 {} 地锁上报 {}", pileCode, MaintenanceFrames.decodeLockReport(frame.body()));
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
                        READ_REALTIME -> log.debug("桩 {} 上送了平台下行帧 {}，忽略", pileCode, type.label());
                default -> log.debug("桩 {} 帧 {} 未实现业务处理", pileCode, type.label());
            }
        } catch (RuntimeException e) {
            telemetry.failure(e);
            log.error("处理桩 {} 帧 {} 失败: {}", pileCode, type.label(), e.getMessage(), e);
            logFrame("IN", frame, pileCode, inbound.wireHex(), e.getMessage());
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
