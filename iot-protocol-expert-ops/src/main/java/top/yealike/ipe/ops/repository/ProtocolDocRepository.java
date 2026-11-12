package top.yealike.ipe.ops.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.ProtocolDoc;

public interface ProtocolDocRepository extends JpaRepository<ProtocolDoc, Long> {
    Optional<ProtocolDoc> findByProtocolAndVersion(String protocol, String version);
}
