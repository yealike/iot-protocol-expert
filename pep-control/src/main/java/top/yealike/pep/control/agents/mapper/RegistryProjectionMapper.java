package top.yealike.pep.control.agents.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.agents.domain.PlatformRecords.RegistryProjection;

public interface RegistryProjectionMapper {

    int upsert(
            @Param("id") UUID id,
            @Param("resourceType") String resourceType,
            @Param("releaseId") UUID releaseId,
            @Param("serviceName") String serviceName,
            @Param("providerId") String providerId,
            @Param("desiredState") String desiredState,
            @Param("observedState") String observedState,
            @Param("error") String error);

    List<RegistryProjection> list();

    List<RegistryProjection> find(@Param("resourceType") String resourceType, @Param("releaseId") UUID releaseId);

    int markObserved(
            @Param("resourceType") String resourceType,
            @Param("releaseId") UUID releaseId,
            @Param("observedState") String observedState,
            @Param("error") String error);
}
