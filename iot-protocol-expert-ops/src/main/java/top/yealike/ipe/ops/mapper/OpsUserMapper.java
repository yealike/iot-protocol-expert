package top.yealike.ipe.ops.mapper;

import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ops.domain.OpsUser;

public interface OpsUserMapper {

    OpsUser selectByUsername(@Param("username") String username);

    int insert(OpsUser user);

    default Optional<OpsUser> findByUsername(String username) {
        return Optional.ofNullable(selectByUsername(username));
    }
}
