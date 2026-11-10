package top.yealike.ipe.protocol.codec;

import java.util.*;

/**
 * V1.6 appendix 13.1. Reserved codes are retained with an explicit label.
 */
public final class StopReasons {
    private StopReasons() {
    }

    public static final Map<Integer, String> ALL;

    static {
        Map<Integer, String> values = new LinkedHashMap<>();
        values.put(0x40, "结束充电，APP远程停止");
        values.put(0x41, "结束充电，SOC达到100%");
        values.put(0x42, "结束充电，电量满足设定条件");
        values.put(0x43, "结束充电，金额满足设定条件");
        values.put(0x44, "结束充电，时间满足设定条件");
        values.put(0x45, "结束充电，手动停止");
        values.put(0x46, "预留停止原因");
        values.put(0x47, "预留停止原因");
        values.put(0x48, "预留停止原因");
        values.put(0x49, "预留停止原因");
        values.put(0x4A, "启动失败，控制系统故障");
        values.put(0x4B, "启动失败，控制导引断开");
        values.put(0x4C, "启动失败，断路器跳位");
        values.put(0x4D, "启动失败，电表通信中断");
        values.put(0x4E, "启动失败，余额不足");
        values.put(0x4F, "启动失败，充电模块故障");
        values.put(0x50, "启动失败，急停开入");
        values.put(0x51, "启动失败，防雷器异常");
        values.put(0x52, "启动失败，BMS未就绪");
        values.put(0x53, "启动失败，温度异常");
        values.put(0x54, "启动失败，电池反接");
        values.put(0x55, "启动失败，电子锁异常");
        values.put(0x56, "启动失败，合闸失败");
        values.put(0x57, "启动失败，绝缘异常");
        values.put(0x58, "预留停止原因");
        values.put(0x59, "启动失败，BHM超时");
        values.put(0x5A, "启动失败，BRM超时");
        values.put(0x5B, "启动失败，BCP超时");
        values.put(0x5C, "启动失败，BRO AA超时");
        values.put(0x5D, "启动失败，BCS超时");
        values.put(0x5E, "启动失败，BCL超时");
        values.put(0x5F, "启动失败，BSM超时");
        values.put(0x60, "启动失败，GB2015 BHM电压不允许充电");
        values.put(0x61, "启动失败，BRO_AA与BCP电压差大于5%");
        values.put(0x62, "启动失败，预充阶段BRO_AA变为BRO_00");
        values.put(0x63, "启动失败，主机配置超时");
        values.put(0x64, "启动失败，充电机未准备就绪，未回CRO AA");
        values.put(0x65, "预留停止原因");
        values.put(0x66, "预留停止原因");
        values.put(0x67, "预留停止原因");
        values.put(0x68, "预留停止原因");
        values.put(0x69, "预留停止原因");
        values.put(0x6A, "异常中止，系统闭锁");
        values.put(0x6B, "异常中止，导引断开");
        values.put(0x6C, "异常中止，断路器跳位");
        values.put(0x6D, "异常中止，电表通信中断");
        values.put(0x6E, "异常中止，余额不足");
        values.put(0x6F, "异常中止，交流保护动作");
        values.put(0x70, "异常中止，直流保护动作");
        values.put(0x71, "异常中止，充电模块故障");
        values.put(0x72, "异常中止，急停开入");
        values.put(0x73, "异常中止，防雷器异常");
        values.put(0x74, "异常中止，温度异常");
        values.put(0x75, "异常中止，输出异常");
        values.put(0x76, "异常中止，充电无流");
        values.put(0x77, "异常中止，电子锁异常");
        values.put(0x78, "预留停止原因");
        values.put(0x79, "异常中止，总充电电压异常");
        values.put(0x7A, "异常中止，总充电电流异常");
        values.put(0x7B, "异常中止，单体电压异常");
        values.put(0x7C, "异常中止，电池组过温");
        values.put(0x7D, "异常中止，最高单体电压异常");
        values.put(0x7E, "异常中止，最高电池组过温");
        values.put(0x7F, "异常中止，BMV单体电压异常");
        values.put(0x80, "异常中止，BMT电池组过温");
        values.put(0x81, "异常中止，电池状态异常");
        values.put(0x82, "异常中止，车辆禁止充电");
        values.put(0x83, "异常中止，充电桩断电");
        values.put(0x84, "异常中止，电池总状态超时");
        values.put(0x85, "异常中止，电池充电要求超时");
        values.put(0x86, "异常中止，电池状态信息超时");
        values.put(0x87, "异常中止，BMS中止报文超时");
        values.put(0x88, "异常中止，BMS充电统计超时");
        values.put(0x89, "异常中止，对侧CCS超时");
        values.put(0x8A, "预留停止原因");
        values.put(0x8B, "预留停止原因");
        values.put(0x8C, "预留停止原因");
        values.put(0x8D, "预留停止原因");
        values.put(0x8E, "预留停止原因");
        values.put(0x8F, "预留停止原因");
        values.put(0x90, "未知原因停止");
        ALL = Collections.unmodifiableMap(values);
    }
}
