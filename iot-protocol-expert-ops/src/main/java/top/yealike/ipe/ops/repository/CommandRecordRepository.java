package top.yealike.ipe.ops.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.CommandRecord;

public interface CommandRecordRepository extends JpaRepository<CommandRecord, Long> {
    List<CommandRecord> findTop100ByDeviceIdOrderByIdDesc(Long deviceId);

    List<CommandRecord> findTop100ByOrderByIdDesc();
}
