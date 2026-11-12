package top.yealike.ipe.ops.device.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import top.yealike.ipe.ops.dto.OpsDtos;

/**
 * 充电枪实时值 → 通道型属性 的翻译层。
 *
 * <p>协议通道的入参仍是原来的字段（{@code gunNo} / {@code state} / {@code outputVoltage}...），
 * 这里把它们映射到物模型属性，避免协议模块随平台模型变化而改代码。 这是「接入层与模型层解耦」的落点：协议怎么说不重要，平台按属性存。
 */
public final class ChargerConnectorMapper {

    private ChargerConnectorMapper() {}

    /** 云快充实时状态码 → 物模型枚举。0 空闲 / 1 故障 / 2 准备 / 3 充电。 */
    public static String stateOf(int raw) {
        return switch (raw) {
            case 1 -> "fault";
            case 2 -> "preparing";
            case 3 -> "charging";
            default -> "idle";
        };
    }

    /** 云快充 0x13 实时数据帧。 */
    public static Map<String, Object> fromRealtime(OpsDtos.RealtimeRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gunState", stateOf(request.state()));
        values.put("plugged", request.plugged() != 0);
        if (request.outputVoltage() != null) {
            values.put("voltage", request.outputVoltage());
        }
        if (request.outputCurrent() != null) {
            values.put("current", request.outputCurrent());
        }
        if (request.soc() >= 0) {
            values.put("soc", request.soc());
        }
        if (request.batteryMaxTemp() > 0) {
            values.put("batteryTemp", request.batteryMaxTemp());
        }
        if (request.gunLineTemp() > 0) {
            values.put("gunLineTemp", request.gunLineTemp());
        }
        if (request.chargedEnergy() != null) {
            values.put("chargeEnergy", request.chargedEnergy());
        }
        if (request.chargedAmount() != null) {
            values.put("chargeAmount", request.chargedAmount());
        }
        if (request.remainMinutes() > 0) {
            values.put("remainMinutes", request.remainMinutes());
        }
        if (request.transactionNo() != null && !request.transactionNo().isBlank()) {
            values.put("transactionNo", request.transactionNo());
        }
        return values;
    }

    /** OCPP 侧状态与计量值。参数为 null 表示本次不上报该属性。 */
    public static Map<String, Object> fromOcpp(
            int state,
            int plugged,
            BigDecimal voltage,
            BigDecimal current,
            Integer soc,
            BigDecimal energy,
            BigDecimal amount,
            String transactionNo) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gunState", stateOf(state));
        values.put("plugged", plugged != 0);
        if (voltage != null) {
            values.put("voltage", voltage);
        }
        if (current != null) {
            values.put("current", current);
        }
        if (soc != null && soc >= 0) {
            values.put("soc", soc);
        }
        if (energy != null) {
            values.put("chargeEnergy", energy);
        }
        if (amount != null) {
            values.put("chargeAmount", amount);
        }
        if (transactionNo != null && !transactionNo.isBlank()) {
            values.put("transactionNo", transactionNo);
        }
        return values;
    }
}
