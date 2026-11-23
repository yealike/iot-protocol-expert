package top.yealike.pep.simulator.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.simulator.mapper.dto.ChargeRecordRow;

public interface SimulatorStateMapper {

    String selectSnapshot(@Param("stateKey") String stateKey);

    int upsertState(
            @Param("stateKey") String stateKey,
            @Param("deviceId") Long deviceId,
            @Param("protocol") String protocol,
            @Param("pileCode") String pileCode,
            @Param("snapshot") String snapshot);

    int insertChargingRecords(@Param("rows") List<ChargeRecordRow> rows);
}
