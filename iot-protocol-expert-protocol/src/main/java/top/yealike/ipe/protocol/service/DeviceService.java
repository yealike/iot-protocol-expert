package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.domain.DeviceEntity;
import top.yealike.ipe.protocol.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 设备档案与在线状态。 */
@Service
public class DeviceService {

    private final DeviceRepository repository;
    private final ProtocolProperties properties;

    public DeviceService(DeviceRepository repository, ProtocolProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public DeviceEntity onLogin(RegistrationFrames.Login login) {
        DeviceEntity device = repository.findByPileCode(login.pileCode()).orElseGet(() -> {
            if (!properties.isAutoRegister()) {
                throw new DeviceRejectedException("未登记的桩: " + login.pileCode());
            }
            DeviceEntity created = new DeviceEntity();
            created.setPileCode(login.pileCode());
            created.setRegisteredAt(LocalDateTime.now());
            return created;
        });
        device.setPileType(login.pileType());
        device.setGunCount(Math.max(login.gunCount(), 1));
        device.setProtocolVersion(login.protocolVersion());
        device.setProgramVersion(login.programVersion());
        device.setNetworkType(login.networkType());
        device.setSimCard(login.simCard());
        device.setOperator(login.operator());
        device.setOnline(true);
        device.setOnlineAt(LocalDateTime.now());
        device.setLastSeen(LocalDateTime.now());
        if (device.getStationName() == null) {
            device.setStationName("演示充电站");
        }
        return repository.save(device);
    }

    @Transactional
    public void markOnline(String pileCode) {
        repository.findByPileCode(pileCode).ifPresent(d -> {
            d.setOnline(true);
            d.setOnlineAt(LocalDateTime.now());
            d.setLastSeen(LocalDateTime.now());
            repository.save(d);
        });
    }

    @Transactional
    public void markOffline(String pileCode) {
        repository.findByPileCode(pileCode).ifPresent(d -> {
            d.setOnline(false);
            repository.save(d);
        });
    }

    @Transactional
    public void touch(String pileCode) {
        repository.findByPileCode(pileCode).ifPresent(d -> {
            if (!d.isOnline()) {
                d.setOnline(true);
                d.setOnlineAt(LocalDateTime.now());
            }
            d.setLastSeen(LocalDateTime.now());
            repository.save(d);
        });
    }

    public DeviceEntity find(String pileCode) {
        return repository.findByPileCode(pileCode)
                .orElseThrow(() -> new DeviceRejectedException("桩不存在: " + pileCode));
    }

    public boolean exists(String pileCode) {
        return repository.findByPileCode(pileCode).isPresent();
    }

    public List<DeviceEntity> list() {
        return repository.findAllByOrderByOnlineDescPileCodeAsc();
    }

    public DeviceEntity save(DeviceEntity device) {
        return repository.save(device);
    }

    /** 登录被拒绝：未登记或明确不允许接入。 */
    public static class DeviceRejectedException extends RuntimeException {
        public DeviceRejectedException(String message) {
            super(message);
        }
    }

    /** 设备不在线。 */
    public static class DeviceOfflineException extends RuntimeException {
        public DeviceOfflineException(String message) {
            super(message);
        }
    }
}