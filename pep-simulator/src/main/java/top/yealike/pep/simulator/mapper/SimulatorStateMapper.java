package top.yealike.pep.simulator.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SimulatorStateMapper {

    class ChargeRecordRow {
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

    String selectSnapshot(@Param("stateKey") String stateKey);

    int upsertState(
            @Param("stateKey") String stateKey,
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("pileCode") String pileCode,
            @Param("snapshot") String snapshot);

    int insertChargingRecords(@Param("rows") List<ChargeRecordRow> rows);
}
