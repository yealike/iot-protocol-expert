package top.yealike.pep.control.access.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface AccessMapper {

    record ProjectScope(UUID projectId, UUID tenantId, String projectState, String tenantState) {}

    boolean isActiveUser(@Param("userId") long userId);

    ProjectScope findProject(@Param("projectId") UUID projectId);

    boolean isProjectMember(@Param("projectId") UUID projectId, @Param("userId") long userId);

    boolean isTenantMember(@Param("tenantId") UUID tenantId, @Param("userId") long userId);

    boolean hasPermission(
            @Param("userId") long userId,
            @Param("permission") String permission,
            @Param("tenantId") UUID tenantId,
            @Param("projectId") UUID projectId);

    boolean hasPlatformPermission(@Param("userId") long userId, @Param("permission") String permission);
}
