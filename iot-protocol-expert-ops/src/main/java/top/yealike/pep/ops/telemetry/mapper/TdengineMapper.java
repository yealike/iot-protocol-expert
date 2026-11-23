package top.yealike.pep.ops.telemetry.mapper;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.telemetry.domain.TelemetryRow;

/** TDengine 专用 Mapper；使用独立 SqlSessionFactory，不参与业务库事务。 */
public interface TdengineMapper {

    int createDatabase(@Param("database") String database);

    int createStable(@Param("database") String database);

    int createTable(@Param("database") String database, @Param("table") String table);

    int insertSample(
            @Param("database") String database,
            @Param("table") String table,
            @Param("ts") Timestamp ts,
            @Param("source") String source,
            @Param("voltage") Double voltage,
            @Param("current") Double current,
            @Param("power") Double power,
            @Param("gunTemp") Double gunTemp,
            @Param("batteryTemp") Double batteryTemp,
            @Param("soc") Double soc);

    List<TelemetryRow> query(@Param("database") String database, @Param("table") String table);
}
