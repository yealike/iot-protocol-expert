package top.yealike.ipe.simulator.runtime;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import top.yealike.ipe.simulator.config.SimulatorProperties;

/** 为每台并发模拟的充电桩维护独立连接、状态和账单。 */
@Component
public class ChargerFleet {
    private static final Logger log = LoggerFactory.getLogger(ChargerFleet.class);
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Map<Long, SimulatorRuntime> devices = new ConcurrentHashMap<>();
    private final SimulatorProperties defaults;
    private final ProtocolTelemetry telemetry;
    private final OpsDeviceClient opsDevices;
    private final SimulatorRuntime interactive;

    public ChargerFleet(
            SimulatorProperties defaults,
            ProtocolTelemetry telemetry,
            OpsDeviceClient opsDevices,
            SimulatorRuntime interactive) {
        this.defaults = defaults;
        this.telemetry = telemetry;
        this.opsDevices = opsDevices;
        this.interactive = interactive;
    }

    public synchronized Map<String, Object> start(Long id) {
        if (id == null) throw new IllegalArgumentException("请选择设备");
        if (id.equals(interactive.state().get("deviceId"))) throw new IllegalArgumentException("设备已在充电实验室运行");
        if (devices.containsKey(id)) return devices.get(id).state();
        SimulatorProperties config = new SimulatorProperties();
        BeanUtils.copyProperties(defaults, config);
        config.setStateFile(SimulatorRuntime.stateFileForDevice(defaults.getStateFile(), id));
        config.setAutoSelectDeviceId(null);
        config.setAutoConnect(false);
        config.setPileCode("00000000000000");
        config.setGunCount(1);
        config.setProtocol("ykc16");
        SimulatorRuntime runtime = new SimulatorRuntime(config, telemetry);
        runtime.setOpsDevices(opsDevices);
        try {
            runtime.init(false);
            runtime.selectDevice(id, false);
            devices.put(id, runtime);
            return runtime.state();
        } catch (RuntimeException e) {
            runtime.abort();
            throw e;
        }
    }

    public synchronized Map<String, Object> selectInteractive(Long id) {
        if (contains(id)) throw new IllegalArgumentException("设备正在并发模拟，请先停止");
        return interactive.selectDevice(id);
    }

    public synchronized void stop(Long id) {
        SimulatorRuntime runtime = devices.remove(id);
        if (runtime != null) runtime.shutdown();
    }

    public boolean contains(Long id) {
        return devices.containsKey(id);
    }

    public List<Map<String, Object>> states() {
        return devices.values().stream().map(SimulatorRuntime::state).toList();
    }

    public synchronized Map<String, Object> action(Long id, String gun, String action, String code) {
        SimulatorRuntime runtime = devices.get(id);
        if (runtime == null) throw new IllegalArgumentException("设备未在并发模拟中");
        String message = switch (action) {
            case "plug" -> runtime.plug(gun);
            case "unplug" -> runtime.unplug(gun);
            case "start" -> runtime.startByCard(gun);
            case "stop" -> runtime.stopLocal(gun);
            case "report" -> runtime.reportRealtime(gun);
            case "fault" -> code == null ? runtime.fault(gun, true) : runtime.fault(gun, true, code);
            case "clear-fault" -> runtime.fault(gun, false);
            default -> throw new IllegalArgumentException("不支持的操作");
        };
        return Map.of("message", message, "state", runtime.state());
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        devices.forEach((id, runtime) -> {
            if (!pending.add(id)) return;
            workers.execute(() -> {
                try {
                    if (devices.get(id) == runtime) runtime.tick();
                } catch (RuntimeException e) {
                    log.warn("设备 {} 模拟周期失败", id, e);
                } finally {
                    pending.remove(id);
                }
            });
        });
    }

    @PreDestroy
    public synchronized void shutdown() {
        devices.values().forEach(SimulatorRuntime::shutdown);
        devices.clear();
        workers.shutdownNow();
    }
}
