package top.yealike.ipe.protocol.codec.frames;

import java.math.BigDecimal;
import java.util.*;
import top.yealike.ipe.protocol.codec.*;

/** V1.6 §7.3–7.10. Field widths follow the tables, rather than inconsistent sample lengths. */
public final class BmsFrames {
    private BmsFrames() {}

    public record Field(String name, String label, int bytes, int decimals, int offset, String format) {}

    public record Message(String transactionNo, String pileCode, String gunNo, Map<String, Object> values) {}

    private static Field n(String name, String label, int bytes, int decimals, int offset) {
        return new Field(name, label, bytes, decimals, offset, "number");
    }

    private static Field s(String name, String label, int bytes, String format) {
        return new Field(name, label, bytes, 0, 0, format);
    }

    private static Field bits(String name, String label, int bytes) {
        return s(name, label, bytes, "bits");
    }

    public static List<Field> fields(FrameType type) {
        return switch (type) {
            case CHARGE_HANDSHAKE ->
                List.of(
                        s("version", "BMS通信版本", 3, "hex"), n("batteryType", "电池类型", 1, 0, 0),
                        n("ratedCapacityAh", "额定容量Ah", 2, 1, 0), n("ratedVoltage", "额定总电压V", 2, 1, 0),
                        s("manufacturer", "生产厂商", 4, "ascii"), s("batterySerial", "电池组序号", 4, "hex"),
                        n("productionYear", "生产年份", 1, 0, 1985), n("productionMonth", "生产月份", 1, 0, 0),
                        n("productionDay", "生产日期", 1, 0, 0), n("chargeCycles", "充电次数", 3, 0, 0),
                        n("ownership", "产权", 1, 0, 0), n("reserved", "预留", 1, 0, 0),
                        s("vin", "车辆VIN", 17, "ascii"), s("softwareVersion", "BMS软件版本", 8, "hex"));
            case PARAM_CONFIG ->
                List.of(
                        n("maxCellVoltage", "单体最高允许电压V", 2, 2, 0),
                        n("maxCurrent", "最高允许电流A", 2, 1, -400),
                        n("capacityKwh", "标称总能量kWh", 2, 1, 0),
                        n("maxVoltage", "最高允许总电压V", 2, 1, 0),
                        n("maxTemperature", "最高允许温度℃", 1, 0, -50),
                        n("soc", "SOC%", 2, 1, 0),
                        n("batteryVoltage", "当前电池电压(原始值，规范未标倍率)", 2, 0, 0),
                        n("chargerMaxVoltage", "桩最高输出电压V", 2, 1, 0),
                        n("chargerMinVoltage", "桩最低输出电压V", 2, 1, 0),
                        n("chargerMaxCurrent", "桩最大输出电流A", 2, 1, -400),
                        n("chargerMinCurrent", "桩最小输出电流A", 2, 1, -400));
            case CHARGE_END ->
                List.of(
                        n("soc", "结束SOC%", 1, 0, 0),
                        n("minCellVoltage", "单体最低电压V", 2, 2, 0),
                        n("maxCellVoltage", "单体最高电压V", 2, 2, 0),
                        n("minTemperature", "最低温度℃", 1, 0, -50),
                        n("maxTemperature", "最高温度℃", 1, 0, -50),
                        n("minutes", "累计充电分钟", 2, 0, 0),
                        n("energy", "输出能量kWh", 2, 1, 0),
                        n("chargerNo", "充电机编号(含1偏移)", 4, 0, 1));
            case ERROR_REPORT ->
                List.of(
                        bits("bmsRecognitionTimeout", "BMS接收辨识超时/预留", 1), bits("bmsReadyTimeout", "BMS接收对时和准备超时/预留", 1),
                        bits("bmsChargingTimeout", "BMS接收充电状态和中止超时/预留", 1),
                                bits("bmsStatisticsTimeout", "BMS接收统计超时/其他", 1),
                        bits("chargerRecognitionTimeout", "桩接收辨识超时/预留", 1),
                                bits("chargerReadyTimeout", "桩接收参数和准备超时/预留", 1),
                        bits("chargerChargingTimeout", "桩接收总状态需求中止超时/预留", 1),
                                bits("chargerStatisticsTimeout", "桩接收统计超时/其他", 1));
            case BMS_ABORT, CHARGER_ABORT ->
                List.of(bits("reason", "中止原因(每2位)", 1), bits("fault", "故障原因(每2位)", 2), bits("error", "错误原因(每2位)", 1));
            case BMS_DEMAND ->
                List.of(
                        n("demandVoltage", "需求电压V", 2, 1, 0),
                        n("demandCurrent", "需求电流A", 2, 1, -400),
                        n("chargeMode", "充电模式1恒压2恒流", 1, 0, 0),
                        n("measuredVoltage", "BMS电压V", 2, 1, 0),
                        n("measuredCurrent", "BMS电流A", 2, 1, -400),
                        bits("maxCellVoltageAndGroup", "最高单体电压低12位/组号高4位", 2),
                        n("soc", "SOC%", 1, 0, 0),
                        n("remainMinutes", "剩余分钟", 2, 0, 0),
                        n("outputVoltage", "桩输出电压V", 2, 1, 0),
                        n("outputCurrent", "桩输出电流A", 2, 1, -400),
                        n("minutes", "累计分钟", 2, 0, 0));
            case BMS_INFO ->
                List.of(
                        n("maxCellNo", "最高单体电压编号", 1, 0, 1),
                        n("maxTemperature", "最高温度℃", 1, 0, -50),
                        n("maxTemperatureProbe", "最高温度探针", 1, 0, 1),
                        n("minTemperature", "最低温度℃", 1, 0, -50),
                        n("minTemperatureProbe", "最低温度探针", 1, 0, 1),
                        bits("batteryStatus", "单体电压/SOC/过流/温度(每2位)", 1),
                        bits("chargeStatus", "绝缘/连接/允许充电/预留(每2位)", 1));
            default -> throw new ProtocolException("不是BMS过程帧: " + type);
        };
    }

    public static Map<String, List<String>> bitDefinitions(FrameType type) {
        return switch (type) {
            case ERROR_REPORT ->
                Map.of(
                        "bmsRecognitionTimeout",
                        List.of("crm00Timeout", "crmAATimeout"),
                        "bmsReadyTimeout",
                        List.of("ctsCmlTimeout", "croTimeout"),
                        "bmsChargingTimeout",
                        List.of("ccsTimeout", "cstTimeout"),
                        "bmsStatisticsTimeout",
                        List.of("csdTimeout"),
                        "chargerRecognitionTimeout",
                        List.of("brmTimeout"),
                        "chargerReadyTimeout",
                        List.of("bcpTimeout", "broTimeout"),
                        "chargerChargingTimeout",
                        List.of("bcsTimeout", "bclTimeout", "bstTimeout"),
                        "chargerStatisticsTimeout",
                        List.of("bsdTimeout"));
            case BMS_ABORT ->
                Map.of(
                        "reason",
                        List.of("socTarget", "totalVoltageTarget", "cellVoltageTarget", "chargerAbort"),
                        "fault",
                        List.of(
                                "insulation",
                                "outputConnectorOverheat",
                                "bmsOverheat",
                                "connectorFault",
                                "batteryOverheat",
                                "highVoltageRelay",
                                "point2Voltage",
                                "other"),
                        "error",
                        List.of("overcurrent", "abnormalVoltage"));
            case CHARGER_ABORT ->
                Map.of(
                        "reason",
                        List.of("chargerCondition", "manualStop", "abnormalStop", "bmsAbort"),
                        "fault",
                        List.of(
                                "chargerOverheat",
                                "connectorFault",
                                "internalOverheat",
                                "energyUnavailable",
                                "emergencyStop",
                                "other"),
                        "error",
                        List.of("currentMismatch", "abnormalVoltage"));
            case BMS_INFO ->
                Map.of(
                        "batteryStatus",
                        List.of("cellVoltage", "soc", "overcurrent", "overheat"),
                        "chargeStatus",
                        List.of("insulation", "connector", "chargeAllowed"));
            default -> Map.of();
        };
    }

    public static int bodyLength(FrameType type) {
        return 24 + fields(type).stream().mapToInt(Field::bytes).sum();
    }

    public static Message decode(FrameType type, byte[] body) {
        BinaryReader r = new BinaryReader(body, type.label());
        String tx = r.bcd(16), pile = r.bcd(7), gun = r.bcd(1);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field f : fields(type)) {
            Object value = switch (f.format()) {
                case "ascii" -> r.ascii(f.bytes());
                case "hex" -> HexFormat.of().withUpperCase().formatHex(r.bytes(f.bytes()));
                default -> BigDecimal.valueOf(r.uint(f.bytes()), f.decimals()).add(BigDecimal.valueOf(f.offset()));
            };
            values.put(f.name(), value);
            if (f.format().equals("bits")) {
                long raw = ((BigDecimal) value).longValueExact();
                List<Integer> states = new ArrayList<>();
                for (int bit = 0; bit < f.bytes() * 8; bit += 2) states.add((int) ((raw >>> bit) & 3));
                values.put(f.name() + "Pairs", states);
                List<String> names = bitDefinitions(type).get(f.name());
                if (names != null) {
                    Map<String, Integer> named = new LinkedHashMap<>();
                    for (int i = 0; i < names.size(); i++) named.put(names.get(i), states.get(i));
                    values.put(f.name() + "Status", named);
                }
            }
        }
        if (type == FrameType.BMS_DEMAND) {
            int raw = ((BigDecimal) values.get("maxCellVoltageAndGroup")).intValue();
            values.put("maxCellVoltage", BigDecimal.valueOf(raw & 4095, 2));
            values.put("maxCellGroup", raw >>> 12);
        }
        return new Message(tx, pile, gun, Collections.unmodifiableMap(values));
    }

    public static byte[] encode(FrameType type, Message message) {
        BinaryWriter w = new BinaryWriter()
                .bcd(message.transactionNo(), 16)
                .bcd(message.pileCode(), 7)
                .bcd(message.gunNo(), 1);
        for (Field f : fields(type)) {
            Object v = message.values().get(f.name());
            if (v == null) throw new ProtocolException("缺少BMS字段: " + f.name());
            switch (f.format()) {
                case "ascii" -> w.ascii(v.toString(), f.bytes());
                case "hex" -> {
                    byte[] b = HexFormat.of().parseHex(v.toString());
                    if (b.length != f.bytes()) throw new ProtocolException("字段长度错误: " + f.name());
                    w.bytes(b);
                }
                default -> {
                    long raw = new BigDecimal(v.toString())
                            .subtract(BigDecimal.valueOf(f.offset()))
                            .movePointRight(f.decimals())
                            .longValueExact();
                    if (raw < 0 || raw >= (1L << (8 * f.bytes()))) throw new ProtocolException("BMS字段越界: " + f.name());
                    w.uint(raw, f.bytes());
                }
            }
        }
        return w.toBytes();
    }
}
