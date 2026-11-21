package top.yealike.pep.gateway.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import top.yealike.pep.codec.Frame;
import top.yealike.pep.codec.FrameType;
import top.yealike.pep.codec.frames.MaintenanceFrames;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.codec.frames.RealtimeFrames;
import top.yealike.pep.codec.frames.RegistrationFrames;
import top.yealike.pep.codec.frames.SettingFrames;
import top.yealike.pep.gateway.config.ProtocolProperties;
import top.yealike.pep.gateway.core.DeviceIdentityRegistry;
import top.yealike.pep.gateway.core.ProtocolType;
import top.yealike.pep.gateway.netty.SessionRegistry;
import top.yealike.pep.gateway.ops.OpsClient;
import top.yealike.pep.gateway.ops.OpsDtos;

/** 平台下行命令的统一入口，供运维平台内部命令接口调用。 */
@Service
public class CommandFacade {

    private final SessionRegistry sessions;
    private final OpsClient ops;
    private final ProtocolProperties properties;
    private final DeviceIdentityRegistry identities;

    public CommandFacade(
            SessionRegistry sessions, OpsClient ops, ProtocolProperties properties, DeviceIdentityRegistry identities) {
        this.sessions = sessions;
        this.ops = ops;
        this.properties = properties;
        this.identities = identities;
    }

    public RealtimeFrames.Realtime readRealtime(String deviceSn, String platformGunNo) {
        DeviceIdentityRegistry.Device identity = identity(deviceSn);
        DeviceIdentityRegistry.Connector connector = identity.connectorByPlatformGunNo(platformGunNo);
        Frame ack = request(
                deviceSn,
                FrameType.READ_REALTIME,
                FrameType.REALTIME,
                RealtimeFrames.encodeReadRealtime(
                        new RealtimeFrames.ReadRealtime(identity.thirdPartyDeviceId(), connector.externalYkcGunNo())));
        return platformRealtime(RealtimeFrames.decodeRealtime(ack.body()), identity, connector);
    }

    public SettingFrames.TimeSync timeSync(String deviceSn) {
        DeviceIdentityRegistry.Device identity = identity(deviceSn);
        SettingFrames.TimeSync body = new SettingFrames.TimeSync(identity.thirdPartyDeviceId(), LocalDateTime.now());
        Frame ack = request(deviceSn, FrameType.TIME_SYNC, FrameType.TIME_SYNC_ACK, SettingFrames.encodeTimeSync(body));
        SettingFrames.TimeSync reply = SettingFrames.decodeTimeSyncAck(ack.body());
        return new SettingFrames.TimeSync(deviceSn, reply.time());
    }

    public SettingFrames.ParamSetAck paramSet(String deviceSn, boolean allowWork, int maxPowerPercent) {
        if (maxPowerPercent < 30 || maxPowerPercent > 100) {
            throw new IllegalArgumentException("输出功率范围30~100%");
        }
        String externalPileCode = identity(deviceSn).thirdPartyDeviceId();
        Frame ack = request(
                deviceSn,
                FrameType.PARAM_SET,
                FrameType.PARAM_SET_ACK,
                SettingFrames.encodeParamSet(new SettingFrames.ParamSet(externalPileCode, allowWork, maxPowerPercent)));
        SettingFrames.ParamSetAck result = SettingFrames.decodeParamSetAck(ack.body());
        if (result.success()) {
            ops.patch("YKC16", deviceSn, new OpsDtos.DevicePatch(allowWork, maxPowerPercent));
        }
        return result;
    }

    public MaintenanceFrames.RebootAck reboot(String deviceSn, int control) {
        String externalPileCode = identity(deviceSn).thirdPartyDeviceId();
        Frame ack = request(
                deviceSn,
                FrameType.REBOOT,
                FrameType.REBOOT_ACK,
                MaintenanceFrames.encodeReboot(new MaintenanceFrames.Reboot(externalPileCode, control)));
        return MaintenanceFrames.decodeRebootAck(ack.body());
    }

    public MaintenanceFrames.UpdateAck update(String deviceSn, MaintenanceFrames.Update update) {
        MaintenanceFrames.Update wireUpdate = new MaintenanceFrames.Update(
                identity(deviceSn).thirdPartyDeviceId(),
                update.pileModel(),
                update.power(),
                update.serverAddress(),
                update.port(),
                update.username(),
                update.password(),
                update.filePath(),
                update.control(),
                update.downloadTimeoutMinutes());
        Frame ack =
                request(deviceSn, FrameType.UPDATE, FrameType.UPDATE_ACK, MaintenanceFrames.encodeUpdate(wireUpdate));
        return MaintenanceFrames.decodeUpdateAck(ack.body());
    }

    public OperationFrames.BalanceAck balance(
            String deviceSn, String platformGunNo, String physicalCard, BigDecimal balance) {
        DeviceIdentityRegistry.Device identity = identity(deviceSn);
        DeviceIdentityRegistry.Connector connector = identity.connectorByPlatformGunNo(platformGunNo);
        Frame ack = request(
                deviceSn,
                FrameType.BALANCE_UPDATE,
                FrameType.BALANCE_ACK,
                OperationFrames.encodeBalanceUpdate(new OperationFrames.BalanceUpdate(
                        identity.thirdPartyDeviceId(), connector.externalYkcGunNo(), physicalCard, balance)));
        return OperationFrames.decodeBalanceAck(ack.body());
    }

    public OperationFrames.CardSyncAck cardSync(String deviceSn, List<OperationFrames.OfflineCard> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        String externalPileCode = identity(deviceSn).thirdPartyDeviceId();
        OperationFrames.CardSyncAck result = null;
        for (int i = 0; i < cards.size(); i += 11) {
            Frame ack = request(
                    deviceSn,
                    FrameType.CARD_SYNC,
                    FrameType.CARD_SYNC_ACK,
                    OperationFrames.encodeCardSync(new OperationFrames.CardList(
                            externalPileCode, cards.subList(i, Math.min(i + 11, cards.size())))));
            result = OperationFrames.decodeCardSyncAck(ack.body());
            if (!result.success()) {
                return result;
            }
        }
        return result;
    }

    public OperationFrames.CardClearAck cardClear(String deviceSn, List<String> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        String externalPileCode = identity(deviceSn).thirdPartyDeviceId();
        var results = new java.util.ArrayList<OperationFrames.CardClearAck.CardClearResult>();
        for (int i = 0; i < cards.size(); i += 18) {
            Frame ack = request(
                    deviceSn,
                    FrameType.CARD_CLEAR,
                    FrameType.CARD_CLEAR_ACK,
                    OperationFrames.encodeCardClear(new OperationFrames.CardClear(
                            externalPileCode, cards.subList(i, Math.min(i + 18, cards.size())))));
            results.addAll(OperationFrames.decodeCardClearAck(ack.body()).results());
        }
        return new OperationFrames.CardClearAck(deviceSn, results);
    }

    public OperationFrames.CardQueryAck cardQuery(String deviceSn, List<String> cards) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("卡列表不能为空");
        }
        String externalPileCode = identity(deviceSn).thirdPartyDeviceId();
        var results = new java.util.ArrayList<OperationFrames.CardQueryAck.CardQueryResult>();
        for (int i = 0; i < cards.size(); i += 20) {
            Frame ack = request(
                    deviceSn,
                    FrameType.CARD_QUERY,
                    FrameType.CARD_QUERY_ACK,
                    OperationFrames.encodeCardQuery(new OperationFrames.CardQuery(
                            externalPileCode, cards.subList(i, Math.min(i + 20, cards.size())))));
            results.addAll(OperationFrames.decodeCardQueryAck(ack.body()).results());
        }
        return new OperationFrames.CardQueryAck(deviceSn, results);
    }

    public MaintenanceFrames.LockControlAck lockControl(String deviceSn, String platformGunNo, int action) {
        DeviceIdentityRegistry.Device identity = identity(deviceSn);
        DeviceIdentityRegistry.Connector connector = identity.connectorByPlatformGunNo(platformGunNo);
        Frame ack = request(
                deviceSn,
                FrameType.LOCK_CONTROL,
                FrameType.LOCK_CONTROL_ACK,
                MaintenanceFrames.encodeLockControl(new MaintenanceFrames.LockControl(
                        identity.thirdPartyDeviceId(), Integer.parseInt(connector.externalYkcGunNo()), action)));
        return MaintenanceFrames.decodeLockControlAck(ack.body());
    }

    /** 平台主动下发当前计费模型（0x58），并等待 0x57。 */
    public RegistrationFrames.RateSetAck pushRateModel(String deviceSn) {
        OpsDtos.RateModelView model = ops.activeRateModel();
        if (model == null) {
            throw new IllegalStateException("运维平台未返回计费模型");
        }
        RegistrationFrames.RateModel body = new RegistrationFrames.RateModel(
                identity(deviceSn).thirdPartyDeviceId(),
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
                request(deviceSn, FrameType.RATE_SET, FrameType.RATE_SET_ACK, RegistrationFrames.encodeRateModel(body));
        return RegistrationFrames.decodeRateSetAck(ack.body(), "0x57");
    }

    private DeviceIdentityRegistry.Device identity(String deviceSn) {
        return identities.resolvePlatform(ProtocolType.YKC16, deviceSn);
    }

    private static RealtimeFrames.Realtime platformRealtime(
            RealtimeFrames.Realtime value,
            DeviceIdentityRegistry.Device identity,
            DeviceIdentityRegistry.Connector connector) {
        return new RealtimeFrames.Realtime(
                value.transactionNo(),
                identity.deviceSn(),
                connector.gunNo(),
                value.state(),
                value.gunHomed(),
                value.plugged(),
                value.outputVoltage(),
                value.outputCurrent(),
                value.gunLineTemp(),
                value.gunLineCode(),
                value.soc(),
                value.batteryMaxTemp(),
                value.totalChargeMinutes(),
                value.remainMinutes(),
                value.chargedEnergy(),
                value.lossEnergy(),
                value.chargedAmount(),
                value.hardwareFault(),
                value.rawExtra());
    }

    private Frame request(String deviceSn, FrameType requestType, FrameType ackType, byte[] body) {
        return sessions.request(
                deviceSn,
                requestType,
                ackType,
                body,
                Duration.ofSeconds(Math.max(properties.getPendingTimeoutSeconds(), 3)));
    }
}
