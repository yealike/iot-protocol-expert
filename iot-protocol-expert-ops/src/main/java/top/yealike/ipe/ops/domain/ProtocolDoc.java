package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "protocol_doc")
public class ProtocolDoc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(nullable = false, length = 32)
    public String version;

    @Column(nullable = false, length = 256)
    public String title;

    @Column(length = 512)
    public String source;

    public LocalDateTime createdAt;
}
