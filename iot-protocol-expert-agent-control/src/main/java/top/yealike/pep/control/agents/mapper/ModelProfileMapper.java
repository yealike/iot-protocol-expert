package top.yealike.pep.control.agents.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.agents.domain.ModelProfile;

public interface ModelProfileMapper {

    List<ModelProfile> list();

    List<ModelProfile> find(@Param("id") UUID id);

    List<ModelProfile> findByKey(@Param("key") String key);

    int insert(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("providerType") String providerType,
            @Param("baseUrl") String baseUrl,
            @Param("modelName") String modelName,
            @Param("credentialId") UUID credentialId,
            @Param("optionsJson") String optionsJson,
            @Param("actor") String actor);

    int update(
            @Param("id") UUID id,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("baseUrl") String baseUrl,
            @Param("modelName") String modelName,
            @Param("credentialId") UUID credentialId,
            @Param("optionsJson") String optionsJson,
            @Param("enabled") boolean enabled);

    int setEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);
}
