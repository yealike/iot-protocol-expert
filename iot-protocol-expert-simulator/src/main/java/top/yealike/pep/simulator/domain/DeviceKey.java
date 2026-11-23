package top.yealike.pep.simulator.domain;

/** 模拟器持久化快照的设备维度键。 */
public record DeviceKey(Long deviceId, String protocol, String pileCode) {
    public String value() {
        return (deviceId == null ? "configured" : "device/" + deviceId) + "/" + protocol + "/" + pileCode;
    }
}
