package top.yealike.ipe.protocol.repository;

import top.yealike.ipe.protocol.domain.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<DeviceEntity, Long> {

    Optional<DeviceEntity> findByPileCode(String pileCode);

    List<DeviceEntity> findAllByOrderByOnlineDescPileCodeAsc();
}