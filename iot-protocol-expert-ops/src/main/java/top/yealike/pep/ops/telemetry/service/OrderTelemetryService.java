package top.yealike.pep.ops.telemetry.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.charging.domain.ChargeOrder;
import top.yealike.pep.ops.charging.service.OrderService;
import top.yealike.pep.ops.telemetry.domain.TelemetryPoint;
import top.yealike.pep.ops.telemetry.domain.TelemetryResult;
import top.yealike.pep.ops.telemetry.mapper.OrderTelemetryMapper;

@Service
public class OrderTelemetryService {
    private final OrderTelemetryMapper telemetry;
    private final OrderService orders;
    private final TdengineTelemetryStore store;

    public OrderTelemetryService(OrderTelemetryMapper telemetry, OrderService orders, TdengineTelemetryStore store) {
        this.telemetry = telemetry;
        this.orders = orders;
        this.store = store;
    }

    @Transactional
    public void record(
            ChargeOrder order,
            Instant time,
            String timeSource,
            BigDecimal voltage,
            BigDecimal current,
            BigDecimal power,
            BigDecimal gunTemperature,
            BigDecimal batteryTemperature,
            BigDecimal soc) {
        if (voltage == null
                && current == null
                && power == null
                && gunTemperature == null
                && batteryTemperature == null
                && soc == null) return;
        telemetry.insertSample(
                order.orderNo,
                Timestamp.from(time),
                timeSource,
                voltage,
                current,
                power,
                gunTemperature,
                batteryTemperature,
                soc);
    }

    @Transactional(readOnly = true)
    public TelemetryResult query(String orderNo) {
        orders.require(orderNo);
        boolean pending = telemetry.existsPending(orderNo);
        if (Boolean.TRUE.equals(pending)) throw new IllegalStateException("订单时序数据正在同步，请稍后重试");
        List<TelemetryPoint> rows = store.query(orderNo);
        return new TelemetryResult(rows.size() > 10000 ? rows.subList(0, 10000) : rows, rows.size() > 10000);
    }
}
