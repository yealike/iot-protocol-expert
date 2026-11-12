package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "station")
public class Station {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "station_code", nullable = false, unique = true, length = 64)
    public String stationCode;

    @Column(nullable = false, length = 128)
    public String name;

    @Column(length = 256)
    public String address;

    @Column(nullable = false, length = 64)
    public String timezone = "Asia/Shanghai";

    @Column(nullable = false, length = 16)
    public String status = "ENABLED";

    @Column(precision = 10, scale = 6)
    public java.math.BigDecimal longitude;

    @Column(precision = 10, scale = 6)
    public java.math.BigDecimal latitude;

    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
