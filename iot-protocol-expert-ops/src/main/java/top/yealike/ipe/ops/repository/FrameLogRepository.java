package top.yealike.ipe.ops.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import top.yealike.ipe.ops.domain.FrameLog;

public interface FrameLogRepository extends JpaRepository<FrameLog, Long>, JpaSpecificationExecutor<FrameLog> {
    List<FrameLog> findTop200ByDeviceIdOrderByCreatedAtDesc(Long deviceId);

    long countByDirection(String direction);
}
