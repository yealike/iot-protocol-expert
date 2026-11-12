package top.yealike.ipe.ops.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import top.yealike.ipe.ops.domain.OpsUser;

public interface OpsUserRepository extends JpaRepository<OpsUser, Long> {
    Optional<OpsUser> findByUsername(String username);
}
