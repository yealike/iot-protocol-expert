package top.yealike.pep.ops.charging.domain;

import java.time.LocalDateTime;

public class Station {
    public Long id;

    public String stationCode;

    public String name;

    public String address;

    public String timezone = "Asia/Shanghai";

    public String status = "ENABLED";

    public java.math.BigDecimal longitude;

    public java.math.BigDecimal latitude;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
