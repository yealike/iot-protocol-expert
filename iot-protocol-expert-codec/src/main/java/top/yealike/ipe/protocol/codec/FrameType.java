package top.yealike.ipe.protocol.codec;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 云快充 1.6 帧类型码。桩 -> 平台为奇数，平台 -> 桩为偶数。 */
public enum FrameType {
    LOGIN(0x01, "充电桩登录认证", Direction.UP),
    LOGIN_ACK(0x02, "登录认证应答", Direction.DOWN),
    HEARTBEAT(0x03, "充电桩心跳包", Direction.UP),
    HEARTBEAT_ACK(0x04, "心跳包应答", Direction.DOWN),
    RATE_VERIFY(0x05, "计费模型验证请求", Direction.UP),
    RATE_VERIFY_ACK(0x06, "计费模型验证请求应答", Direction.DOWN),
    RATE_REQUEST(0x09, "充电桩计费模型请求", Direction.UP),
    RATE_RESPONSE(0x0A, "计费模型请求应答", Direction.DOWN),
    READ_REALTIME(0x12, "读取实时监测数据", Direction.DOWN),
    REALTIME(0x13, "上传实时监测数据", Direction.UP),
    CHARGE_HANDSHAKE(0x15, "充电握手", Direction.UP),
    PARAM_CONFIG(0x17, "参数配置", Direction.UP),
    CHARGE_END(0x19, "充电结束", Direction.UP),
    ERROR_REPORT(0x1B, "错误报文", Direction.UP),
    BMS_ABORT(0x1D, "充电阶段 BMS 中止", Direction.UP),
    CHARGER_ABORT(0x21, "充电阶段充电机中止", Direction.UP),
    BMS_DEMAND(0x23, "充电过程 BMS 需求与充电机输出", Direction.UP),
    BMS_INFO(0x25, "充电过程 BMS 信息", Direction.UP),
    START_REQUEST(0x31, "充电桩主动申请启动充电", Direction.UP),
    START_ACK(0x32, "运营平台确认启动充电", Direction.DOWN),
    REMOTE_START_ACK(0x33, "远程启动充电命令回复", Direction.UP),
    REMOTE_START(0x34, "运营平台远程控制启机", Direction.DOWN),
    STOP_ACK(0x35, "远程停机命令回复", Direction.UP),
    REMOTE_STOP(0x36, "运营平台远程停机", Direction.DOWN),
    TRANSACTION(0x3B, "交易记录", Direction.UP),
    TRANSACTION_ACK(0x40, "交易记录确认", Direction.DOWN),
    BALANCE_ACK(0x41, "余额更新应答", Direction.UP),
    BALANCE_UPDATE(0x42, "远程账户余额更新", Direction.DOWN),
    CARD_SYNC_ACK(0x43, "离线卡数据同步应答", Direction.UP),
    CARD_SYNC(0x44, "离线卡数据同步", Direction.DOWN),
    CARD_CLEAR_ACK(0x45, "离线卡数据清除应答", Direction.UP),
    CARD_CLEAR(0x46, "离线卡数据清除", Direction.DOWN),
    CARD_QUERY_ACK(0x47, "离线卡数据查询应答", Direction.UP),
    CARD_QUERY(0x48, "离线卡数据查询", Direction.DOWN),
    PARAM_SET_ACK(0x51, "充电桩工作参数设置应答", Direction.UP),
    PARAM_SET(0x52, "充电桩工作参数设置", Direction.DOWN),
    TIME_SYNC_ACK(0x55, "对时设置应答", Direction.UP),
    TIME_SYNC(0x56, "对时设置", Direction.DOWN),
    RATE_SET_ACK(0x57, "计费模型应答", Direction.UP),
    RATE_SET(0x58, "计费模型设置", Direction.DOWN),
    LOCK_REPORT(0x61, "地锁数据上送", Direction.UP),
    LOCK_CONTROL(0x62, "遥控地锁升锁与降锁命令", Direction.DOWN),
    LOCK_CONTROL_ACK(0x63, "充电桩返回地锁数据", Direction.UP),
    REBOOT_ACK(0x91, "远程重启应答", Direction.UP),
    REBOOT(0x92, "远程重启", Direction.DOWN),
    UPDATE_ACK(0x93, "远程更新应答", Direction.UP),
    UPDATE(0x94, "远程更新", Direction.DOWN),
    PARALLEL_START_REQUEST(0xA1, "充电桩主动申请并充充电", Direction.UP),
    PARALLEL_START_ACK(0xA2, "运营平台确认并充启动充电", Direction.DOWN),
    PARALLEL_START_RESULT(0xA3, "远程并充启机命令回复", Direction.UP),
    PARALLEL_REMOTE_START(0xA4, "运营平台远程控制并充启机", Direction.DOWN);

    public enum Direction {
        UP,
        DOWN
    }

    private static final Map<Integer, FrameType> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(FrameType::code, Function.identity()));

    private final int code;
    private final String label;
    private final Direction direction;

    FrameType(int code, String label, Direction direction) {
        this.code = code;
        this.label = label;
        this.direction = direction;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public Direction direction() {
        return direction;
    }

    public String hex() {
        return String.format("0x%02X", code);
    }

    public static FrameType of(int code) {
        FrameType type = BY_CODE.get(code);
        if (type == null) {
            throw new ProtocolException("未定义的帧类型码: 0x" + String.format("%02X", code));
        }
        return type;
    }

    public static FrameType ofOrNull(int code) {
        return BY_CODE.get(code);
    }
}
