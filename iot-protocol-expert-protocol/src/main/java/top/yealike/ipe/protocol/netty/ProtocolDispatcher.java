package top.yealike.ipe.protocol.netty;

import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import top.yealike.ipe.protocol.codec.InboundFrame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import top.yealike.ipe.protocol.codec.frames.ParallelFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import top.yealike.ipe.protocol.domain.ChargeOrderEntity;
import top.yealike.ipe.protocol.domain.DeviceEntity;
import top.yealike.ipe.protocol.domain.TransactionEntity;
import top.yealike.ipe.protocol.service.ChargeService;
import top.yealike.ipe.protocol.service.DeviceService;
import top.yealike.ipe.protocol.service.FrameLogService;
import top.yealike.ipe.protocol.service.GunStateService;
import top.yealike.ipe.protocol.service.OrderService;
import top.yealike.ipe.protocol.service.RateModelService;
import top.yealike.ipe.protocol.service.TransactionService;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 上行帧业务分发。所有上行帧先落流水，再按类型处理。 */
@Component
public class ProtocolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDispatcher.class);

    private final ProtocolTelemetry telemetry;
    private final SessionRegistry sessions;
    private final DeviceService deviceService;
    private final RateModelService rateModelService;
    private final GunStateService gunStateService;
    private final OrderService orderService;
    private final ChargeService chargeService;
    private final TransactionService transactionService;
    private final FrameLogService frameLogService;

    public ProtocolDispatcher(SessionRegistry sessions, DeviceService deviceService,
                              RateModelService rateModelService, GunStateService gunStateService,
                              OrderService orderService, ChargeService chargeService,
                              TransactionService transactionService, FrameLogService frameLogService, ProtocolTelemetry telemetry) {
        this.telemetry = telemetry;
        this.sessions = sessions;
        this.deviceService = deviceService;
        this.rateModelService = rateModelService;
        this.gunStateService = gunStateService;
        this.orderService = orderService;
        this.chargeService = chargeService;
        this.transactionService = transactionService;
        this.frameLogService = frameLogService;
    }

    public void onLogin(Channel channel, InboundFrame inbound) {
        Frame frame = inbound.frame();
        RegistrationFrames.Login login = RegistrationFrames.decodeLogin(frame.body());
        try {
            DeviceEntity device = deviceService.onLogin(login);
            sessions.bind(login.pileCode(), channel, frame.encrypted());
            frameLogService.inbound(frame, login.pileCode(), inbound.wireHex());
            sessions.send(login.pileCode(), FrameType.LOGIN_ACK,
                    RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), true)), frame.sequence());
            log.info("桩 {} 登录成功（枪数 {}，协议版本 {}，程序 {}）",
                    device.getPileCode(), device.getGunCount(), device.getProtocolVersion(), device.getProgramVersion());
        } catch (RuntimeException e) {
            telemetry.failure(e);
            sessions.bind(login.pileCode(), channel, frame.encrypted());
            frameLogService.inbound(frame, login.pileCode(), inbound.wireHex());
            sessions.send(login.pileCode(), FrameType.LOGIN_ACK,
                    RegistrationFrames.encodeLoginAck(new RegistrationFrames.LoginAck(login.pileCode(), false)), frame.sequence());
            log.warn("桩 {} 登录被拒绝: {}", login.pileCode(), e.getMessage());
            channel.close();
        }
    }

    public void dispatch(String pileCode, InboundFrame inbound) {
        Frame frame = inbound.frame();
        FrameType type = FrameType.ofOrNull(frame.frameType());
        frameLogService.inbound(frame, pileCode, inbound.wireHex());
        if (type == null) {
            telemetry.failure(new IllegalArgumentException("unknown frame type"));
            log.warn("桩 {} 上送未知帧类型 0x{}", pileCode, String.format("%02X", frame.frameType()));
            return;
        }
        // 先唤醒等待应答的业务线程


        try {
            if(type.direction()!=FrameType.Direction.UP)return;
            Object message=top.yealike.ipe.protocol.codec.FramePayloads.decode(type,frame.body());
            try{Object address=message.getClass().getMethod("pileCode").invoke(message);if(!pileCode.equals(address))throw new IllegalArgumentException("帧桩号与登录会话不符");}
            catch(NoSuchMethodException ignored){}catch(ReflectiveOperationException e){throw new IllegalArgumentException("无法解析帧桩号",e);}
            sessions.complete(pileCode,frame);
            switch (type) {
                case HEARTBEAT -> {
                    RegistrationFrames.Heartbeat hb = RegistrationFrames.decodeHeartbeat(frame.body());
                    deviceService.touch(pileCode);
                    sessions.send(pileCode, FrameType.HEARTBEAT_ACK,
                            RegistrationFrames.encodeHeartbeatAck(
                                    new RegistrationFrames.HeartbeatAck(pileCode, hb.gunNo())), frame.sequence());
                }
                case RATE_VERIFY -> {
                    RegistrationFrames.RateVerify verify = RegistrationFrames.decodeRateVerify(frame.body());
                    boolean consistent = rateModelService.verify(verify.modelNo());
                    sessions.send(pileCode, FrameType.RATE_VERIFY_ACK,
                            RegistrationFrames.encodeRateVerifyAck(
                                    new RegistrationFrames.RateVerifyAck(pileCode, verify.modelNo(), consistent)), frame.sequence());
                }
                case RATE_REQUEST -> sessions.send(pileCode, FrameType.RATE_RESPONSE,
                        RegistrationFrames.encodeRateModel(
                                rateModelService.toProtocol(rateModelService.active(), pileCode)), frame.sequence());
                case REALTIME -> {
                    RealtimeFrames.Realtime realtime = RealtimeFrames.decodeRealtime(frame.body());
                    deviceService.touch(pileCode);
                    gunStateService.update(realtime);
                    orderService.applyRealtime(realtime);
                }
                case START_REQUEST -> {
                    OperationFrames.StartRequest request = OperationFrames.decodeStartRequest(frame.body());
                    OperationFrames.StartAck ack = chargeService.handleStartRequest(request);
                    sessions.send(pileCode, FrameType.START_ACK, OperationFrames.encodeStartAck(ack), frame.sequence());
                }
                case REMOTE_START_ACK -> {
                    OperationFrames.RemoteStartAck ack = OperationFrames.decodeRemoteStartAck(frame.body());
                    if (ack.success() && orderService.sameGun(pileCode,ack.gunNo(),ack.transactionNo())) {
                        orderService.markCharging(ack.transactionNo());
                    }
                }
                case STOP_ACK -> {
                    OperationFrames.StopAck ack = OperationFrames.decodeStopAck(frame.body());
                    if (ack.success()) {
                        ChargeOrderEntity order = orderService.active(pileCode, ack.gunNo());
                        if (order != null) {
                            orderService.stop(order.getTransactionNo(), 0x40);
                        }
                    }
                }
                case TRANSACTION -> {
                    Frame decoded = frame;
                    OperationFrames.Transaction tx = OperationFrames.decodeTransaction(decoded.body());
                    TransactionEntity record = transactionService.accept(tx, inbound.wireHex());
                    sessions.send(pileCode, FrameType.TRANSACTION_ACK,
                            OperationFrames.encodeTransactionAck(
                                    new OperationFrames.TransactionAck(tx.transactionNo(), record.isAccepted())), frame.sequence());
                }
                case TIME_SYNC_ACK -> log.debug("桩 {} 对时完成", pileCode);
                case PARAM_SET_ACK, REBOOT_ACK, UPDATE_ACK, RATE_SET_ACK, BALANCE_ACK,
                     CARD_SYNC_ACK, CARD_CLEAR_ACK, CARD_QUERY_ACK, LOCK_CONTROL_ACK ->
                        log.debug("桩 {} 应答 {}", pileCode, type.label());
                case CHARGE_HANDSHAKE, PARAM_CONFIG, CHARGE_END, ERROR_REPORT, BMS_ABORT,
                     CHARGER_ABORT, BMS_DEMAND, BMS_INFO -> {
                    BmsFrames.Message bms=BmsFrames.decode(type,frame.body());
                    log.debug("桩 {} {} 流水={} 字段={}",pileCode,type.label(),bms.transactionNo(),bms.values());
                }
                case LOCK_REPORT -> log.info("桩 {} 地锁上报 {}", pileCode,MaintenanceFrames.decodeLockReport(frame.body()));
                case BALANCE_UPDATE, CARD_SYNC, CARD_CLEAR, CARD_QUERY, LOCK_CONTROL, REBOOT,
                     UPDATE, PARAM_SET, TIME_SYNC, RATE_SET, READ_REALTIME ->
                        log.debug("桩 {} 上送了平台下行帧 {}，忽略", pileCode, type.label());
                case PARALLEL_START_REQUEST -> {
                    var request=ParallelFrames.decodeParallelStartRequest(frame.body());
                    var ack=chargeService.handleStartRequest(new OperationFrames.StartRequest(request.pileCode(),request.gunNo(),request.startType(),request.needPassword(),request.account(),request.passwordHex(),request.vin()));
                    sessions.send(pileCode,FrameType.PARALLEL_START_ACK,ParallelFrames.encodeParallelStartAck(new ParallelFrames.ParallelStartAck(ack.transactionNo(),pileCode,ack.gunNo(),ack.logicalCard(),ack.balance(),ack.success(),ack.failReason(),request.parallelNo())),frame.sequence());
                }
                case PARALLEL_START_RESULT -> {
                    var result=ParallelFrames.decodeParallelStartResult(frame.body());
                    if(result.success())orderService.markCharging(result.transactionNo());else orderService.fail(result.transactionNo(),"并充失败: "+result.failReason());
                }
                case PARALLEL_START_ACK,PARALLEL_REMOTE_START -> log.warn("忽略上送的并充下行帧");
                default -> log.debug("桩 {} 帧 {} 未实现业务处理", pileCode, type.label());
            }
        } catch (RuntimeException e) {
            telemetry.failure(e);
            log.error("处理桩 {} 帧 {} 失败: {}", pileCode, type.label(), e.getMessage(), e);
            frameLogService.error("IN", frame, pileCode, inbound.wireHex(), e.getMessage());
        }
    }
}
