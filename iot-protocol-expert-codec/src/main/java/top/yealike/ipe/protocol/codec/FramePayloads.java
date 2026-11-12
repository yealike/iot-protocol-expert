package top.yealike.ipe.protocol.codec;

import top.yealike.ipe.protocol.codec.frames.*;

/** Complete structured decoder for every frame in V1.6. Shared by consoles and persisted logs. */
public final class FramePayloads {
    private FramePayloads() {}

    public static Object decode(FrameType type, byte[] body) {
        if (type == null) throw new ProtocolException("未知帧类型");
        return switch (type) {
            case LOGIN -> RegistrationFrames.decodeLogin(body);
            case LOGIN_ACK -> RegistrationFrames.decodeLoginAck(body);
            case HEARTBEAT -> RegistrationFrames.decodeHeartbeat(body);
            case HEARTBEAT_ACK -> RegistrationFrames.decodeHeartbeatAck(body);
            case RATE_VERIFY -> RegistrationFrames.decodeRateVerify(body);
            case RATE_VERIFY_ACK -> RegistrationFrames.decodeRateVerifyAck(body);
            case RATE_REQUEST -> RegistrationFrames.decodeRateRequest(body);
            case RATE_RESPONSE, RATE_SET -> RegistrationFrames.decodeRateModel(body);
            case READ_REALTIME -> RealtimeFrames.decodeReadRealtime(body);
            case REALTIME -> RealtimeFrames.decodeRealtime(body);
            case START_REQUEST -> OperationFrames.decodeStartRequest(body);
            case START_ACK -> OperationFrames.decodeStartAck(body);
            case REMOTE_START -> OperationFrames.decodeRemoteStart(body);
            case REMOTE_START_ACK -> OperationFrames.decodeRemoteStartAck(body);
            case REMOTE_STOP -> OperationFrames.decodeRemoteStop(body);
            case STOP_ACK -> OperationFrames.decodeStopAck(body);
            case TRANSACTION -> OperationFrames.decodeTransaction(body);
            case TRANSACTION_ACK -> OperationFrames.decodeTransactionAck(body);
            case BALANCE_UPDATE -> OperationFrames.decodeBalanceUpdate(body);
            case BALANCE_ACK -> OperationFrames.decodeBalanceAck(body);
            case CARD_SYNC -> OperationFrames.decodeCardSync(body);
            case CARD_SYNC_ACK -> OperationFrames.decodeCardSyncAck(body);
            case CARD_CLEAR -> OperationFrames.decodeCardClear(body);
            case CARD_CLEAR_ACK -> OperationFrames.decodeCardClearAck(body);
            case CARD_QUERY -> OperationFrames.decodeCardQuery(body);
            case CARD_QUERY_ACK -> OperationFrames.decodeCardQueryAck(body);
            case PARAM_SET -> SettingFrames.decodeParamSet(body);
            case PARAM_SET_ACK -> SettingFrames.decodeParamSetAck(body);
            case TIME_SYNC -> SettingFrames.decodeTimeSync(body);
            case TIME_SYNC_ACK -> SettingFrames.decodeTimeSyncAck(body);
            case LOCK_REPORT -> MaintenanceFrames.decodeLockReport(body);
            case LOCK_CONTROL -> MaintenanceFrames.decodeLockControl(body);
            case LOCK_CONTROL_ACK -> MaintenanceFrames.decodeLockControlAck(body);
            case REBOOT -> MaintenanceFrames.decodeReboot(body);
            case REBOOT_ACK -> MaintenanceFrames.decodeRebootAck(body);
            case UPDATE -> MaintenanceFrames.decodeUpdate(body);
            case UPDATE_ACK -> MaintenanceFrames.decodeUpdateAck(body);
            case PARALLEL_START_REQUEST -> ParallelFrames.decodeParallelStartRequest(body);
            case PARALLEL_START_ACK -> ParallelFrames.decodeParallelStartAck(body);
            case PARALLEL_START_RESULT -> ParallelFrames.decodeParallelStartResult(body);
            case PARALLEL_REMOTE_START -> ParallelFrames.decodeParallelRemoteStart(body);
            case RATE_SET_ACK -> RegistrationFrames.decodeRateSetAck(body, "0x57");
            case CHARGE_HANDSHAKE,
                    PARAM_CONFIG,
                    CHARGE_END,
                    ERROR_REPORT,
                    BMS_ABORT,
                    CHARGER_ABORT,
                    BMS_DEMAND,
                    BMS_INFO -> BmsFrames.decode(type, body);
        };
    }
}
