package top.yealike.ipe.ops.telemetry;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TelemetryOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(TelemetryOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final TdengineTelemetryStore store;

    public TelemetryOutboxPublisher(JdbcTemplate jdbc, TdengineTelemetryStore store) {
        this.jdbc = jdbc; this.store = store;
    }

    @Scheduled(fixedDelayString = "${ipe.ops.tdengine.flush-delay-ms:1000}")
    @Transactional(rollbackFor = Exception.class)
    public void publish() throws Exception {
        // One publisher across all application instances preserves update order for equal timestamps.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(481923644)", Boolean.class))) return;
        List<TdengineTelemetryStore.Sample> rows = jdbc.query(
                "select id, order_no, sample_time, time_source, voltage, current_amp, power_kw, gun_temp, battery_temp, soc "
                        + "from ipe_ops.order_telemetry_outbox order by id limit 100 for update",
                (rs, n) -> new TdengineTelemetryStore.Sample(rs.getLong(1), rs.getString(2), rs.getTimestamp(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10)));
        for (var row : rows) {
            try { store.write(row); }
            catch (Exception ex) { log.error("TDengine 时序同步失败，样本保留在发件箱，id={}", row.id(), ex); throw ex; }
            jdbc.update("delete from ipe_ops.order_telemetry_outbox where id = ?", row.id());
        }
    }
}
