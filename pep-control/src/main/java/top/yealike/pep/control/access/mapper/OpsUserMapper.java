package top.yealike.pep.control.access.mapper;

import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.domain.OpsUser;

public interface OpsUserMapper {

    OpsUser selectByUsername(@Param("username") String username);

    int insert(OpsUser user);

    default Optional<OpsUser> findByUsername(String username) {
        return Optional.ofNullable(selectByUsername(username));
    }
}
