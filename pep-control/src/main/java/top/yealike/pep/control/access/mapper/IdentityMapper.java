package top.yealike.pep.control.access.mapper;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.access.domain.OpsUser;

public interface IdentityMapper {

    record IdentityRow(long id, String username, boolean enabled, boolean allStations, long authVersion) {}

    record RoleInfo(String code, String name) {}

    List<Long> selectAllUserIds();

    IdentityRow selectIdentityById(@Param("id") long id);

    IdentityRow selectIdentityByUsername(@Param("username") String username);

    List<String> selectRoleCodes(@Param("userId") long userId);

    List<String> selectPermissions(@Param("userId") long userId);

    List<Long> selectStationIds(@Param("userId") long userId);

    List<RoleInfo> selectRoles();

    List<String> selectRolePermissions(@Param("roleCode") String roleCode);

    int deleteRolePermissions(@Param("roleCode") String roleCode);

    int insertRolePermission(@Param("roleCode") String roleCode, @Param("permission") String permission);

    int incrementAuthVersionByRole(@Param("roleCode") String roleCode);

    long countUsername(@Param("username") String username);

    int insertUser(@Param("user") OpsUser user, @Param("allStations") boolean allStations);

    int insertMemberships(@Param("userId") long userId);

    int updateUserAccess(
            @Param("id") long id, @Param("enabled") boolean enabled, @Param("allStations") boolean allStations);

    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    int deleteUserRoles(@Param("userId") long userId);

    int deleteUserStations(@Param("userId") long userId);

    int insertUserRole(@Param("userId") long userId, @Param("roleCode") String roleCode);

    int insertUserStation(@Param("userId") long userId, @Param("stationId") long stationId);

    boolean stationExists(@Param("stationId") long stationId);

    default Optional<IdentityRow> findIdentityById(long id) {
        return Optional.ofNullable(selectIdentityById(id));
    }

    default Optional<IdentityRow> findIdentityByUsername(String username) {
        return Optional.ofNullable(selectIdentityByUsername(username));
    }
}
