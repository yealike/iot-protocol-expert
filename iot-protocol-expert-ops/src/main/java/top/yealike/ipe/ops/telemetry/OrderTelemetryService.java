package top.yealike.ipe.ops.telemetry;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.service.OrderService;

@Service
public class OrderTelemetryService {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final TdengineTelemetryStore store;

    public OrderTelemetryService(JdbcTemplate jdbc, OrderService orders, TdengineTelemetryStore store) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.store = store;
    }

    public record Point(Instant time, String timeSource, BigDecimal voltage, BigDecimal current,
                        BigDecimal power, BigDecimal gunTemperature, BigDecimal batteryTemperature, BigDecimal soc) {}
    public record Result(List<Point> points, boolean truncated) {}

    @Transactional
    public void record(ChargeOrder order, Instant time, String timeSource, BigDecimal voltage,
                       BigDecimal current, BigDecimal power, BigDecimal gunTemperature,
                       BigDecimal batteryTemperature, BigDecimal soc) {
        if (voltage == null && current == null && power == null && gunTemperature == null
                && batteryTemperature == null && soc == null) return;
        jdbc.update("insert into ipe_ops.order_telemetry_outbox "
                        + "(order_no, sample_time, time_source, voltage, current_amp, power_kw, gun_temp, battery_temp, soc) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.orderNo, Timestamp.from(time), timeSource, voltage, current, power, gunTemperature, batteryTemperature, soc);
    }

    @Transactional(readOnly = true)
    public Result query(String orderNo) {
        orders.require(orderNo);
        Boolean pending = jdbc.queryForObject("select exists (select 1 from ipe_ops.order_telemetry_outbox where order_no = ?)",
                Boolean.class, orderNo);
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("订单时序数据正在同步，请稍后重试");
        List<Point> rows = store.query(orderNo);
        return new Result(rows.size() > 10000 ? rows.subList(0, 10000) : rows, rows.size() > 10000);
    }
}
