package top.yealike.ipe.protocol.netty;

import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;

/** 报文留存用的简短摘要。完整字段解析由运维平台按需执行。 */
public final class FrameSummary {

    private FrameSummary() {}

    public static String of(FrameType type, byte[] body) {
        if (type == null) {
            return "未知帧 长度=" + body.length;
        }
        try {
            return switch (type) {
                case LOGIN -> {
                    RegistrationFrames.Login f = RegistrationFrames.decodeLogin(body);
                    yield "登录 桩=" + f.pileCode() + " 枪数=" + f.gunCount();
                }
                case HEARTBEAT -> {
                    RegistrationFrames.Heartbeat f = RegistrationFrames.decodeHeartbeat(body);
                    yield "心跳 桩=" + f.pileCode() + " 枪=" + f.gunNo();
                }
                case REALTIME -> {
                    RealtimeFrames.Realtime f = RealtimeFrames.decodeRealtime(body);
                    yield "实时 桩="
                            + f.pileCode()
                            + " 枪="
                            + f.gunNo()
                            + " 状态="
                            + f.state()
                            + " 电量="
                            + f.chargedEnergy()
                            + "kWh 金额="
                            + f.chargedAmount();
                }
                case START_REQUEST -> {
                    OperationFrames.StartRequest f = OperationFrames.decodeStartRequest(body);
                    yield "主动启动 桩=" + f.pileCode() + " 枪=" + f.gunNo();
                }
                case START_ACK -> {
                    OperationFrames.StartAck f = OperationFrames.decodeStartAck(body);
                    yield "启动确认 流水=" + f.transactionNo() + " 成功=" + f.success();
                }
                case REMOTE_START_ACK -> {
                    OperationFrames.RemoteStartAck f = OperationFrames.decodeRemoteStartAck(body);
                    yield "远程启动应答 流水=" + f.transactionNo() + " 成功=" + f.success();
                }
                case STOP_ACK -> {
                    OperationFrames.StopAck f = OperationFrames.decodeStopAck(body);
                    yield "停机应答 桩=" + f.pileCode() + " 枪=" + f.gunNo() + " 成功=" + f.success();
                }
                case TRANSACTION -> {
                    OperationFrames.Transaction f = OperationFrames.decodeTransaction(body);
                    yield "交易记录 流水=" + f.transactionNo() + " 电量=" + f.totalEnergy() + "kWh 金额=" + f.totalAmount();
                }
                case TRANSACTION_ACK -> {
                    OperationFrames.TransactionAck f = OperationFrames.decodeTransactionAck(body);
                    yield "交易确认 流水=" + f.transactionNo() + " 成功=" + f.success();
                }
                default -> type.label() + " 长度=" + body.length;
            };
        } catch (RuntimeException e) {
            return type.label() + " 解析失败: " + e.getMessage();
        }
    }
}
