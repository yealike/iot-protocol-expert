package top.yealike.ipe.ops.telemetry;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.mapper.OrderTelemetryMapper;

@Component
public class TelemetryOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(TelemetryOutboxPublisher.class);
    private final OrderTelemetryMapper telemetry;
    private final TdengineTelemetryStore store;

    public TelemetryOutboxPublisher(OrderTelemetryMapper telemetry, TdengineTelemetryStore store) {
        this.telemetry = telemetry;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${ipe.ops.tdengine.flush-delay-ms:1000}")
    @Transactional(rollbackFor = Exception.class)
    public void publish() throws Exception {
        // One publisher across all application instances preserves update order for equal timestamps.
        if (!Boolean.TRUE.equals(telemetry.tryAdvisoryLock())) return;
        List<TdengineTelemetryStore.Sample> rows = telemetry.lockPendingSamples();
        for (var row : rows) {
            try {
                store.write(row);
            } catch (Exception ex) {
                log.error("TDengine 时序同步失败，样本保留在发件箱，id={}", row.id(), ex);
                throw ex;
            }
            telemetry.deleteOutboxById(row.id());
        }
    }
}
