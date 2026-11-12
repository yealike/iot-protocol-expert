package top.yealike.ipe.protocol.ocpp16;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** OCPP 键值配置，ChangeConfiguration/GetConfiguration 的内存实现。 */
@Component
public class OcppConfigurationStore {

    private final Map<String, Map<String, String>> values = new ConcurrentHashMap<>();

    public Map<String, String> get(String chargePointId) {
        return new LinkedHashMap<>(values.computeIfAbsent(chargePointId, id -> defaults()));
    }

    public boolean isReadonly(String key) {
        return "ChargePointSerialNumber".equals(key)
                || "ChargePointModel".equals(key)
                || "ChargePointVendor".equals(key)
                || "SupportedFeatureProfiles".equals(key);
    }

    public void set(String chargePointId, String key, String value) {
        values.computeIfAbsent(chargePointId, id -> defaults()).put(key, value);
    }

    public void remove(String chargePointId) {
        values.remove(chargePointId);
    }

    private static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("HeartbeatInterval", "300");
        map.put("MeterValueSampleInterval", "15");
        map.put("ClockAlignedDataInterval", "0");
        map.put("ConnectionTimeOut", "60");
        map.put("ResetRetries", "1");
        map.put(
                "MeterValuesSampledData",
                "Energy.Active.Import.Register,Power.Active.Import,Voltage,Current.Import,SoC");
        map.put("NumberOfConnectors", "2");
        map.put("AuthorizeRemoteTxRequests", "false");
        map.put("LocalAuthorizeOffline", "true");
        map.put("SupportedFeatureProfiles", "Core,FirmwareManagement,LocalAuthListManagement,RemoteTrigger");
        return map;
    }
}
