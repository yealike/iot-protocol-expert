package top.yealike.pep.control.telemetry.mapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.telemetry.TdengineTelemetryStore;

public interface OrderTelemetryMapper {

    int insertSample(
            @Param("orderNo") String orderNo,
            @Param("sampleTime") Timestamp sampleTime,
            @Param("timeSource") String timeSource,
            @Param("voltage") BigDecimal voltage,
            @Param("current") BigDecimal current,
            @Param("power") BigDecimal power,
            @Param("gunTemperature") BigDecimal gunTemperature,
            @Param("batteryTemperature") BigDecimal batteryTemperature,
            @Param("soc") BigDecimal soc);

    boolean existsPending(@Param("orderNo") String orderNo);

    Boolean tryAdvisoryLock();

    List<TdengineTelemetryStore.Sample> selectPendingSamples(@Param("limit") int limit);

    int deleteOutboxById(@Param("id") long id);

    default List<TdengineTelemetryStore.Sample> lockPendingSamples() {
        return selectPendingSamples(100);
    }
}
