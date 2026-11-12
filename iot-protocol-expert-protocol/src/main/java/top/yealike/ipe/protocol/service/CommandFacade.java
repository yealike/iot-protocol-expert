package top.yealike.ipe.protocol.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import top.yealike.ipe.protocol.ops.OpsClient;
import top.yealike.ipe.protocol.ops.OpsDtos;

/** 平台下行命令的统一入口，供运维平台内部命令接口调用。 */
@Service
public class CommandFacade {

    private final SessionRegistry sessions;
    private final OpsClient ops;
    private final ProtocolProperties properties;

    public CommandFacade(SessionRegistry sessions, OpsClient ops, ProtocolProperties properties) {
        this.sessions = sessions;
        this.ops = ops;
        this.properties = properties;
    }

    public RealtimeFrames.Realtime readRealtime(String pileCode, String gunNo) {
        Frame ack = request(
                pileCode,
                FrameType.READ_REALTIME,
                FrameType.REALTIME,
                RealtimeFrames.encodeReadRealtime(new RealtimeFrames.ReadRealtime(pileCode, gunNo)));
        return RealtimeFrames.decodeRealtime(ack.body());
    }

    public SettingFrames.TimeSync timeSync(String pileCode) {
        SettingFrames.TimeSync body = new SettingFrames.TimeSync(pileCode, LocalDateTime.now());
        Frame ack = request(pileCode, FrameType.TIME_SYNC, FrameType.TIME_SYNC_ACK, SettingFrames.encodeTimeSync(body));
        return SettingFrames.decodeTimeSyncAck(ack.body());
    }

    public SettingFrames.ParamSetAck paramSet(String pileCode, boolean allowWork, int maxPowerPercent) {
        if (maxPowerPercent < 30 || maxPowerPercent > 100) {
            throw new IllegalArgumentException("输出功率范围30~100%");
        }
        Frame ack = request(
                pileCode,
                FrameType.PARAM_SET,
                FrameType.PARAM_SET_ACK,
                SettingFrames.encodeParamSet(new SettingFrames.ParamSet(pileCode, allowWork, maxPowerPercent)));
        SettingFrames.ParamSetAck result = SettingFrames.decodeParamSetAck(ack.body());
        if (result.success()) {
            ops.patch("YKC16", pileCode, new OpsDtos.DevicePatch(allowWork, maxPowerPercent));
        }
        return result;
    }

    public MaintenanceFrames.RebootAck reboot(String pileCode, int control) {
        Frame ack = request(
                pileCode,
                FrameType.REBOOT,
                FrameType.REBOOT_ACK,
                MaintenanceFrames.encodeReboot(new MaintenanceFrames.Reboot(pileCode, control)));
        return MaintenanceFrames.decodeRebootAck(ack.body());
    }

    public MaintenanceFrames.UpdateAck update(String pileCode, MaintenanceFrames.Update update) {
        Frame ack = request(pileCode, FrameType.UPDATE, FrameType.UPDATE_ACK, MaintenanceFrames.encodeUpdate(update));
        return MaintenanceFrames.decodeUpdateAck(ack.body());
    }

    public OperationFrames.BalanceAck balance(String pileCode, String gunNo, String physicalCard, BigDecimal balance) {
        Frame ack = request(
                pileCode,
                FrameType.BALANCE_UPDATE,
                FrameType.BALANCE_ACK,
                OperationFrames.encodeBalanceUpdate(
                        new OperationFrames.BalanceUpdate(pileCode, gunNo, physicalCard, balance)));
        return OperationFrames.decodeBalanceAck(ack.body());
    }

    public OperationFrames.CardSyncAck cardSync(String pileCode, List<OperationFrames.OfflineCard> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        OperationFrames.CardSyncAck result = null;
        for (int i = 0; i < cards.size(); i += 11) {
            Frame ack = request(
                    pileCode,
                    FrameType.CARD_SYNC,
                    FrameType.CARD_SYNC_ACK,
                    OperationFrames.encodeCardSync(
                            new OperationFrames.CardList(pileCode, cards.subList(i, Math.min(i + 11, cards.size())))));
            result = OperationFrames.decodeCardSyncAck(ack.body());
            if (!result.success()) {
                return result;
            }
        }
        return result;
    }

    public OperationFrames.CardClearAck cardClear(String pileCode, List<String> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        var results = new java.util.ArrayList<OperationFrames.CardClearAck.CardClearResult>();
        for (int i = 0; i < cards.size(); i += 18) {
            Frame ack = request(
                    pileCode,
                    FrameType.CARD_CLEAR,
                    FrameType.CARD_CLEAR_ACK,
                    OperationFrames.encodeCardClear(
                            new OperationFrames.CardClear(pileCode, cards.subList(i, Math.min(i + 18, cards.size())))));
            results.addAll(OperationFrames.decodeCardClearAck(ack.body()).results());
        }
        return new OperationFrames.CardClearAck(pileCode, results);
    }

    public OperationFrames.CardQueryAck cardQuery(String pileCode, List<String> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        var results = new java.util.ArrayList<OperationFrames.CardQueryAck.CardQueryResult>();
        for (int i = 0; i < cards.size(); i += 20) {
            Frame ack = request(
                    pileCode,
                    FrameType.CARD_QUERY,
                    FrameType.CARD_QUERY_ACK,
                    OperationFrames.encodeCardQuery(
                            new OperationFrames.CardQuery(pileCode, cards.subList(i, Math.min(i + 20, cards.size())))));
            results.addAll(OperationFrames.decodeCardQueryAck(ack.body()).results());
        }
        return new OperationFrames.CardQueryAck(pileCode, results);
    }

    public MaintenanceFrames.LockControlAck lockControl(String pileCode, int gunNo, int action) {
        Frame ack = request(
                pileCode,
                FrameType.LOCK_CONTROL,
                FrameType.LOCK_CONTROL_ACK,
                MaintenanceFrames.encodeLockControl(new MaintenanceFrames.LockControl(pileCode, gunNo, action)));
        return MaintenanceFrames.decodeLockControlAck(ack.body());
    }

    /** 平台主动下发当前计费模型（0x58），并等待 0x57。 */
    public RegistrationFrames.RateSetAck pushRateModel(String pileCode) {
        OpsDtos.RateModelView model = ops.activeRateModel();
        if (model == null) {
            throw new IllegalStateException("运维平台未返回计费模型");
        }
        RegistrationFrames.RateModel body = new RegistrationFrames.RateModel(
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
        Frame ack =
                request(pileCode, FrameType.RATE_SET, FrameType.RATE_SET_ACK, RegistrationFrames.encodeRateModel(body));
        return RegistrationFrames.decodeRateSetAck(ack.body(), "0x57");
    }

    private Frame request(String pileCode, FrameType requestType, FrameType ackType, byte[] body) {
        return sessions.request(
                pileCode,
                requestType,
                ackType,
                body,
                Duration.ofSeconds(Math.max(properties.getPendingTimeoutSeconds(), 3)));
    }
}
