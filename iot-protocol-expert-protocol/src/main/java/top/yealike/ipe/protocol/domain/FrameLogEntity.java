package top.yealike.ipe.protocol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 上下行帧流水。 */
@Entity
@Table(name = "frame_log", indexes = {
        @Index(name = "idx_frame_pile", columnList = "pileCode"),
        @Index(name = "idx_frame_time", columnList = "createdAt")
})
public class FrameLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String direction;
    @Column(length = 32)
    private String pileCode;
    private int frameType;
    @Column(length = 64)
    private String frameLabel;
    private int sequenceNo;
    private boolean encrypted;

    @Column(length = 1024)
    private String bodyHex;
    @Column(length = 2048)
    private String wireHex;
    @Column(length = 512)
    private String summary;
    @Column(length = 256)
    private String errorMessage;

    private LocalDateTime createdAt;

    @jakarta.persistence.Transient
    public Object getDecoded() {
        try {return top.yealike.ipe.protocol.codec.FramePayloads.decode(top.yealike.ipe.protocol.codec.FrameType.of(frameType),top.yealike.ipe.protocol.codec.FrameCodec.parseHex(bodyHex));}
        catch(RuntimeException e) {return java.util.Map.of("error",e.getMessage());}
    }
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getPileCode() {
        return pileCode;
    }

    public void setPileCode(String pileCode) {
        this.pileCode = pileCode;
    }

    public int getFrameType() {
        return frameType;
    }

    public void setFrameType(int frameType) {
        this.frameType = frameType;
    }

    public String getFrameLabel() {
        return frameLabel;
    }

    public void setFrameLabel(String frameLabel) {
        this.frameLabel = frameLabel;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(int sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getBodyHex() {
        return bodyHex;
    }

    public void setBodyHex(String bodyHex) {
        this.bodyHex = bodyHex;
    }

    public String getWireHex() {
        return wireHex;
    }

    public void setWireHex(String wireHex) {
        this.wireHex = wireHex;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}