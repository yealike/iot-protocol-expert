package top.yealike.pep.modelgateway.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import top.yealike.pep.modelgateway.catalog.mapper.CatalogMapper;

/** 模型目录的 MyBatis 访问层；SQL 统一维护在 mapper/catalog/CatalogMapper.xml。 */
@Repository
public class CatalogRepository {

    private final CatalogMapper mapper;

    public CatalogRepository(CatalogMapper mapper) {
        this.mapper = mapper;
    }

    public void insertProvider(
            UUID id, String code, String name, String baseUrl, byte[] cipher, byte[] nonce, String keyId) {
        mapper.insertProvider(id, code, name, baseUrl, cipher, nonce, keyId);
    }

    public List<CatalogRecords.Provider> listProviders() {
        return mapper.listProviders();
    }

    public Optional<CatalogRecords.Provider> findProvider(String code) {
        return Optional.ofNullable(mapper.findProvider(code));
    }

    public Optional<CatalogRecords.Provider> findProviderById(UUID id) {
        return Optional.ofNullable(mapper.findProviderById(id));
    }

    public void insertProfile(
            UUID id,
            String key,
            String displayName,
            UUID providerId,
            String upstreamModel,
            Integer contextWindow,
            int maxOutputTokens,
            int weightNum,
            int weightDen,
            String countingMode) {
        mapper.insertProfile(
                id,
                key,
                displayName,
                providerId,
                upstreamModel,
                contextWindow,
                maxOutputTokens,
                weightNum,
                weightDen,
                countingMode);
    }

    public List<CatalogRecords.ModelProfile> listProfiles() {
        return mapper.listProfiles();
    }

    public Optional<CatalogRecords.ModelProfile> findProfile(String key) {
        return Optional.ofNullable(mapper.findProfile(key));
    }
}
