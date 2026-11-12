package top.yealike.ipe.ops.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "protocol_doc_section")
public class ProtocolDocSection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "doc_id", nullable = false)
    public Long docId;

    @Column(nullable = false, length = 24)
    public String protocol;

    @Column(nullable = false, length = 32)
    public String version;

    @Column(name = "frame_or_action", length = 64)
    public String frameOrAction;

    @Column(length = 256)
    public String heading;

    @Column(nullable = false, columnDefinition = "text")
    public String content;

    @Column(length = 128)
    public String locator;

    @Column(length = 512)
    public String keywords;
}
