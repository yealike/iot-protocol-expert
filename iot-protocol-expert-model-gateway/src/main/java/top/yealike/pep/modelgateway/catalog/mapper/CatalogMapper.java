package top.yealike.pep.modelgateway.catalog.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.modelgateway.catalog.domain.ModelProfile;
import top.yealike.pep.modelgateway.catalog.domain.Provider;

public interface CatalogMapper {

    int insertProvider(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("baseUrl") String baseUrl,
            @Param("cipher") byte[] cipher,
            @Param("nonce") byte[] nonce,
            @Param("keyId") String keyId);

    List<Provider> listProviders();

    Provider findProvider(@Param("code") String code);

    Provider findProviderById(@Param("id") UUID id);

    int insertProfile(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("providerId") UUID providerId,
            @Param("upstreamModel") String upstreamModel,
            @Param("contextWindow") Integer contextWindow,
            @Param("maxOutputTokens") int maxOutputTokens,
            @Param("weightNum") int weightNum,
            @Param("weightDen") int weightDen,
            @Param("countingMode") String countingMode);

    List<ModelProfile> listProfiles();

    ModelProfile findProfile(@Param("key") String key);
}
