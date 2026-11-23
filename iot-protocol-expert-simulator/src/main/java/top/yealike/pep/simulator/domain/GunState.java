package top.yealike.pep.simulator.domain;

/** 模拟枪口状态。 */
public enum GunState {
    OFFLINE(0, "离线"),
    FAULT(1, "故障"),
    IDLE(2, "空闲"),
    CHARGING(3, "充电");

    private final int code;
    private final String label;

    GunState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }
}
