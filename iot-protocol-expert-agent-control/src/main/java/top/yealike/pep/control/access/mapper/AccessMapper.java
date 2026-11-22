package top.yealike.pep.control.access.mapper;

import org.apache.ibatis.annotations.Param;

public interface AccessMapper {

    boolean isActiveUser(@Param("userId") long userId);

    boolean hasPlatformPermission(@Param("userId") long userId, @Param("permission") String permission);
}
