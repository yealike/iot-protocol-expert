package top.yealike.pep.simulator.mapper.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模拟器完成账单的持久化行。 */
public class ChargeRecordRow {
    public String stateKey;
    public String transactionNo;
    public Long deviceId;
    public String protocol;
    public String pileCode;
    public String gunNo;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public BigDecimal meterStart;
    public BigDecimal meterEnd;
    public BigDecimal totalEnergy;
    public BigDecimal lossTotalEnergy;
    public BigDecimal totalAmount;
    public int stopReason;
    public String billJson;
}
