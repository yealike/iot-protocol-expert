package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import top.yealike.ipe.protocol.domain.FrameLogEntity;
import top.yealike.ipe.protocol.repository.FrameLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帧流水记录与摘要。
 */
@Service
public class FrameLogService {

    private final FrameLogRepository repository;

    public FrameLogService(FrameLogRepository repository) {
        this.repository = repository;
    }

    public void inbound(Frame frame, String pileCode, String wireHex) {
        save("IN", frame, pileCode, wireHex, null);
    }

    public void outbound(Frame frame, String pileCode, String wireHex) {
        save("OUT", frame, pileCode, wireHex, null);
    }

    public void error(String direction, Frame frame, String pileCode, String wireHex, String message) {
        save(direction, frame, pileCode, wireHex, message);
    }

    private void save(String direction, Frame frame, String pileCode, String wireHex, String error) {
        FrameLogEntity log = new FrameLogEntity();
        log.setDirection(direction);
        log.setPileCode(pileCode);
        log.setFrameType(frame.frameType());
        FrameType type = FrameType.ofOrNull(frame.frameType());
        log.setFrameLabel(type == null ? "未知帧" : type.label());
        log.setSequenceNo(frame.sequence());
        log.setEncrypted(frame.encrypted());
        log.setBodyHex(truncate(frame.bodyHex(), 1000));
        log.setWireHex(truncate(wireHex, 2000));
        log.setSummary(summarize(type, frame.body()));
        log.setErrorMessage(error);
        log.setCreatedAt(LocalDateTime.now());
        repository.save(log);
    }

    public List<FrameLogEntity> recent(int limit, String pileCode) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        return pileCode == null || pileCode.isBlank()
                ? repository.findAllByOrderByCreatedAtDesc(page)
                : repository.findByPileCodeOrderByCreatedAtDesc(pileCode, page);
    }

    public long inboundCount() {
        return repository.countByDirection("IN");
    }

    public long outboundCount() {
        return repository.countByDirection("OUT");
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String summarize(FrameType type, byte[] body) {
        if (type == null) {
            return "未知帧";
        }
        try {
            return switch (type) {
                case LOGIN -> {
                    RegistrationFrames.Login f = RegistrationFrames.decodeLogin(body);
                    yield "登录 桩=" + f.pileCode() + " 协议v" + (f.protocolVersion() / 10.0) + " 枪数=" + f.gunCount();
                }
                case LOGIN_ACK -> {
                    RegistrationFrames.LoginAck f = RegistrationFrames.decodeLoginAck(body);
                    yield "登录应答 桩=" + f.pileCode() + " 结果=" + (f.success() ? "成功" : "失败");
                }
                case HEARTBEAT -> {
                    RegistrationFrames.Heartbeat f = RegistrationFrames.decodeHeartbeat(body);
                    yield "心跳 桩=" + f.pileCode() + " 枪=" + f.gunNo() + " 状态=" + (f.fault() ? "故障" : "正常");
                }
                case RATE_VERIFY -> {
                    RegistrationFrames.RateVerify f = RegistrationFrames.decodeRateVerify(body);
                    yield "计费验证 桩=" + f.pileCode() + " 模型=" + f.modelNo();
                }
                case RATE_VERIFY_ACK -> {
                    RegistrationFrames.RateVerifyAck f = RegistrationFrames.decodeRateVerifyAck(body);
                    yield "计费验证应答 模型=" + f.modelNo() + " 一致=" + f.consistent();
                }
                case RATE_REQUEST -> "计费模型请求 桩=" + RegistrationFrames.decodeRateRequest(body).pileCode();
                case RATE_RESPONSE, RATE_SET -> {
                    RegistrationFrames.RateModel f = RegistrationFrames.decodeRateModel(body);
                    yield "计费模型 桩=" + f.pileCode() + " 编号=" + f.modelNo()
                            + " 尖=" + f.sharpEnergy() + "+" + f.sharpService();
                }
                case READ_REALTIME -> {
                    RealtimeFrames.ReadRealtime f = RealtimeFrames.decodeReadRealtime(body);
                    yield "读取实时 桩=" + f.pileCode() + " 枪=" + f.gunNo();
                }
                case REALTIME -> {
                    RealtimeFrames.Realtime f = RealtimeFrames.decodeRealtime(body);
                    yield "实时 桩=" + f.pileCode() + " 枪=" + f.gunNo() + " 状态=" + f.state()
                            + " 电压=" + f.outputVoltage() + "V 电流=" + f.outputCurrent()
                            + "A SOC=" + f.soc() + "% 电量=" + f.chargedEnergy() + "kWh 金额=" + f.chargedAmount();
                }
                case START_REQUEST -> {
                    OperationFrames.StartRequest f = OperationFrames.decodeStartRequest(body);
                    yield "主动启动 桩=" + f.pileCode() + " 枪=" + f.gunNo() + " 方式=" + f.startType();
                }
                case START_ACK -> {
                    OperationFrames.StartAck f = OperationFrames.decodeStartAck(body);
                    yield "启动确认 流水=" + f.transactionNo() + " 成功=" + f.success() + " 原因=" + f.failReason();
                }
                case REMOTE_START -> {
                    OperationFrames.RemoteStart f = OperationFrames.decodeRemoteStart(body);
                    yield "远程启动 流水=" + f.transactionNo() + " 桩=" + f.pileCode() + " 枪=" + f.gunNo();
                }
                case REMOTE_START_ACK -> {
                    OperationFrames.RemoteStartAck f = OperationFrames.decodeRemoteStartAck(body);
                    yield "远程启动应答 流水=" + f.transactionNo() + " 成功=" + f.success() + " 原因=" + f.failReason();
                }
                case REMOTE_STOP -> {
                    OperationFrames.RemoteStop f = OperationFrames.decodeRemoteStop(body);
                    yield "远程停机 桩=" + f.pileCode() + " 枪=" + f.gunNo();
                }
                case STOP_ACK -> {
                    OperationFrames.StopAck f = OperationFrames.decodeStopAck(body);
                    yield "停机应答 桩=" + f.pileCode() + " 枪=" + f.gunNo() + " 成功=" + f.success();
                }
                case TRANSACTION -> {
                    OperationFrames.Transaction f = OperationFrames.decodeTransaction(body);
                    yield "交易记录 流水=" + f.transactionNo() + " 电量=" + f.totalEnergy()
                            + "kWh 金额=" + f.totalAmount() + " 停止原因=0x" + String.format("%02X", f.stopReason());
                }
                case TRANSACTION_ACK -> {
                    OperationFrames.TransactionAck f = OperationFrames.decodeTransactionAck(body);
                    yield "交易确认 流水=" + f.transactionNo() + " 成功=" + f.success();
                }
                case TIME_SYNC -> "对时 桩=" + SettingFrames.decodeTimeSync(body).pileCode()
                        + " 时间=" + SettingFrames.decodeTimeSync(body).time();
                case TIME_SYNC_ACK -> "对时应答 桩=" + SettingFrames.decodeTimeSyncAck(body).pileCode();
                case PARAM_SET -> {
                    SettingFrames.ParamSet f = SettingFrames.decodeParamSet(body);
                    yield "参数设置 桩=" + f.pileCode() + " 允许工作=" + f.allowWork() + " 功率=" + f.maxPowerPercent() + "%";
                }
                case PARAM_SET_ACK -> "参数设置应答 成功=" + SettingFrames.decodeParamSetAck(body).success();
                case RATE_SET_ACK ->
                        "计费模型设置应答 成功=" + RegistrationFrames.decodeRateSetAck(body, "0x57").success();
                case REBOOT -> "远程重启 桩=" + MaintenanceFrames.decodeReboot(body).pileCode();
                case REBOOT_ACK -> "重启应答 成功=" + MaintenanceFrames.decodeRebootAck(body).success();
                case UPDATE -> "远程更新 桩=" + MaintenanceFrames.decodeUpdate(body).pileCode()
                        + " 文件=" + MaintenanceFrames.decodeUpdate(body).filePath();
                case UPDATE_ACK -> "更新应答 状态=" + MaintenanceFrames.decodeUpdateAck(body).status();
                case BALANCE_UPDATE -> "余额更新 桩=" + OperationFrames.decodeBalanceUpdate(body).pileCode();
                case BALANCE_ACK -> "余额更新应答 结果=" + OperationFrames.decodeBalanceAck(body).result();
                case CARD_SYNC -> "离线卡同步 张数=" + OperationFrames.decodeCardSync(body).cards().size();
                case CARD_SYNC_ACK -> "离线卡同步应答 成功=" + OperationFrames.decodeCardSyncAck(body).success();
                case CARD_CLEAR -> "离线卡清除 张数=" + OperationFrames.decodeCardClear(body).physicalCards().size();
                case CARD_CLEAR_ACK ->
                        "离线卡清除应答 条数=" + OperationFrames.decodeCardClearAck(body).results().size();
                case CARD_QUERY -> "离线卡查询 张数=" + OperationFrames.decodeCardQuery(body).physicalCards().size();
                case CARD_QUERY_ACK ->
                        "离线卡查询应答 条数=" + OperationFrames.decodeCardQueryAck(body).results().size();
                case LOCK_CONTROL -> "地锁控制 桩=" + MaintenanceFrames.decodeLockControl(body).pileCode();
                case LOCK_CONTROL_ACK -> "地锁控制应答 成功=" + MaintenanceFrames.decodeLockControlAck(body).success();
                case CHARGE_HANDSHAKE,PARAM_CONFIG,CHARGE_END,ERROR_REPORT,BMS_ABORT,CHARGER_ABORT,BMS_DEMAND,BMS_INFO -> {
                    var m=top.yealike.ipe.protocol.codec.frames.BmsFrames.decode(type,body);
                    yield truncate(type.label()+" 流水="+m.transactionNo()+" "+m.values(),500);
                }
                default -> type.label() + " 长度=" + body.length;
            };
        } catch (RuntimeException e) {
            return type.label() + " 解析失败: " + e.getMessage();
        }
    }
}