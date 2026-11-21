package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;

/** Expire only charger sessions created by the gateway telemetry endpoint. */
@Component
public class GatewayPresenceMonitor {
    private final DeviceInstanceMapper devices;
    private final long ttlSeconds;

    public GatewayPresenceMonitor(
            DeviceInstanceMapper devices, @Value("${IPE_GATEWAY_ONLINE_TTL_SECONDS:60}") long ttlSeconds) {
        this.devices = devices;
        this.ttlSeconds = Math.max(10, ttlSeconds);
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void expire() {
        LocalDateTime now = LocalDateTime.now();
        devices.expireGatewayDevices(now.minusSeconds(ttlSeconds), now);
    }
}
