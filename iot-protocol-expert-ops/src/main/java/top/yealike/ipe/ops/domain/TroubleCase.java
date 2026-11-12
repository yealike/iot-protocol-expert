package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trouble_case")
public class TroubleCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "device_id")
    public Long deviceId;

    @Column(name = "order_no", length = 40)
    public String orderNo;

    @Column(length = 24)
    public String protocol;

    @Column(length = 1024)
    public String question;

    @Column(name = "result_json", columnDefinition = "text")
    public String resultJson;

    @Column(name = "created_by", length = 64)
    public String createdBy;

    public LocalDateTime createdAt;
}
