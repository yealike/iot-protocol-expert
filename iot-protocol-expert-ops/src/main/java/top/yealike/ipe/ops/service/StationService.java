package top.yealike.ipe.ops.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.device.repository.DevicePropertyLatestRepository;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.domain.Station;
import top.yealike.ipe.ops.repository.BillRepository;
import top.yealike.ipe.ops.repository.ChargeOrderRepository;
import top.yealike.ipe.ops.repository.StationRepository;

/**
 * 站点服务。
 *
 * <p>站点概览的枪位统计从「gun_state 表按状态计数」改为「设备属性按枚举值计数」， 这样同一段代码对风机、储能也成立——只要它们的物模型里有枚举型状态属性。
 */
@Service
public class StationService {

    private final StationRepository stations;
    private final DeviceInstanceRepository devices;
    private final DevicePropertyLatestRepository properties;
    private final ChargeOrderRepository orders;
    private final BillRepository bills;

    public StationService(
            StationRepository stations,
            DeviceInstanceRepository devices,
            DevicePropertyLatestRepository properties,
            ChargeOrderRepository orders,
            BillRepository bills) {
        this.stations = stations;
        this.devices = devices;
        this.properties = properties;
        this.orders = orders;
        this.bills = bills;
    }

    public List<Station> list() {
        return stations.findAll();
    }

    public Station get(Long id) {
        return stations.findById(id).orElseThrow(() -> new IllegalArgumentException("电站不存在: " + id));
    }

    public Station byCode(String code) {
        return stations.findByStationCode(code).orElseThrow(() -> new IllegalArgumentException("电站不存在: " + code));
    }

    @Transactional
    public Station ensureDefault(String code, String name) {
        return stations.findByStationCode(code).orElseGet(() -> {
            Station station = new Station();
            station.stationCode = code;
            station.name = name;
            station.timezone = "Asia/Shanghai";
            station.status = "ENABLED";
            station.createdAt = LocalDateTime.now();
            station.updatedAt = LocalDateTime.now();
            return stations.save(station);
        });
    }

    @Transactional
    public Station save(Station input) {
        Station station = input.id == null ? new Station() : get(input.id);
        if (input.stationCode == null || input.stationCode.isBlank()) {
            throw new IllegalArgumentException("站点编码不能为空");
        }
        stations.findByStationCode(input.stationCode).ifPresent(other -> {
            if (!other.id.equals(station.id)) {
                throw new IllegalArgumentException("站点编码已存在: " + input.stationCode);
            }
        });
        station.stationCode = input.stationCode.trim();
        station.name = input.name;
        station.address = input.address;
        station.timezone = input.timezone == null || input.timezone.isBlank() ? "Asia/Shanghai" : input.timezone;
        station.status = input.status == null ? "ENABLED" : input.status;
        station.longitude = input.longitude;
        station.latitude = input.latitude;
        station.updatedAt = LocalDateTime.now();
        if (station.id == null) {
            station.createdAt = LocalDateTime.now();
        }
        return stations.save(station);
    }

    public record StationSummary(
            Long stationId,
            String stationCode,
            String name,
            String address,
            String timezone,
            String status,
            BigDecimal longitude,
            BigDecimal latitude,
            long deviceCount,
            long onlineCount,
            long availableGuns,
            long chargingGuns,
            long faultGuns,
            long orderCount,
            BigDecimal energy,
            BigDecimal orderAmount,
            BigDecimal deviceBillAmount,
            BigDecimal estimateAmount,
            long unassignedBills,
            LocalDate from,
            LocalDate to) {}

    public StationSummary summary(Long stationId, LocalDate from, LocalDate to) {
        Station station = get(stationId);
        ZoneId zone = ZoneId.of(station.timezone == null ? "Asia/Shanghai" : station.timezone);
        LocalDate startDate = from == null ? LocalDate.now(zone) : from;
        LocalDate endDate = to == null ? startDate : to;
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        List<DeviceInstance> stationDevices = devices.findByStationId(stationId);
        List<Long> deviceIds = stationDevices.stream().map(device -> device.id).toList();
        List<Long> chargerIds = stationDevices.stream()
                .filter(device -> "CHARGER".equals(device.deviceType))
                .map(device -> device.id)
                .toList();

        long deviceCount =
                stationDevices.stream().filter(device -> device.enabled).count();
        long onlineCount =
                stationDevices.stream().filter(DeviceInstance::isOnline).count();

        var order = orders.summarizeStation(stationId, ChargeOrder.Status.SETTLED, start, end);
        var deviceBills = bills.summarizeStation(stationId, "DEVICE_BILL", start, end);
        var estimates = bills.summarizeStation(stationId, ChargeOrder.AMOUNT_SOURCE_ESTIMATE, start, end);

        return new StationSummary(
                stationId,
                station.stationCode,
                station.name,
                station.address,
                station.timezone,
                station.status,
                station.longitude,
                station.latitude,
                deviceCount,
                onlineCount,
                countGunState(chargerIds, "idle", deviceIds),
                countGunState(chargerIds, "charging", deviceIds),
                countGunState(chargerIds, "fault", deviceIds),
                order == null || order.getC() == null ? 0 : order.getC(),
                order == null ? BigDecimal.ZERO : order.getE(),
                order == null ? BigDecimal.ZERO : order.getA(),
                deviceBills == null ? BigDecimal.ZERO : deviceBills.getA(),
                estimates == null ? BigDecimal.ZERO : estimates.getA(),
                bills.countByStationIdIsNull(),
                startDate,
                endDate);
    }

    private long countGunState(List<Long> chargerIds, String state, List<Long> allIds) {
        if (chargerIds.isEmpty() || allIds.isEmpty()) {
            return 0;
        }
        return properties.countByPropertyIdAndValueTextAndDeviceIdIn("gunState", state, chargerIds);
    }

    public List<DeviceInstance> devicesOf(Long stationId, String protocol) {
        get(stationId);
        return devices.findByStationId(stationId).stream()
                .filter(device -> protocol == null
                        || protocol.isBlank()
                        || DeviceService.normalizeProtocol(device.protocol)
                                .equals(DeviceService.normalizeProtocol(protocol)))
                .toList();
    }
}
