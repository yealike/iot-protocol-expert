package top.yealike.pep.modelgateway.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import top.yealike.pep.modelgateway.catalog.domain.ModelProfile;
import top.yealike.pep.modelgateway.catalog.domain.Provider;
import top.yealike.pep.modelgateway.catalog.domain.ResolvedModel;
import top.yealike.pep.modelgateway.catalog.domain.Sealed;
import top.yealike.pep.modelgateway.support.ApiException;

@Service
public class CatalogService {

    private final CatalogRepository repository;
    private final SecretCipher cipher;

    public CatalogService(CatalogRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public Provider registerProvider(String code, String name, String baseUrl, String apiKey) {
        requireCode(code);
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null) {
            throw ApiException.badRequest("MODEL_REQUEST_INVALID", "供应商地址必须是受控 HTTP(S) 地址");
        }
        Sealed sealed = cipher.seal(apiKey);
        repository.insertProvider(
                UUID.randomUUID(), code, name, baseUrl, sealed.ciphertext(), sealed.nonce(), sealed.keyId());
        return repository.findProvider(code).orElseThrow();
    }

    public List<Provider> providers() {
        return repository.listProviders();
    }

    public ModelProfile registerModel(
            String key,
            String displayName,
            String providerCode,
            String upstreamModel,
            Integer contextWindow,
            int maxOutputTokens,
            String countingMode) {
        requireModelKey(key);
        Provider provider = repository
                .findProvider(providerCode)
                .orElseThrow(() -> ApiException.notFound("供应商不存在：" + providerCode));
        int output = Math.max(1, maxOutputTokens);
        String mode = countingMode == null || countingMode.isBlank() ? "CONSERVATIVE_ESTIMATE" : countingMode;
        repository.insertProfile(
                UUID.randomUUID(), key, displayName, provider.id(), upstreamModel, contextWindow, output, 1, 1, mode);
        return repository.findProfile(key).orElseThrow();
    }

    public List<ModelProfile> models() {
        return repository.listProfiles();
    }

    public ResolvedModel resolve(String key) {
        ModelProfile profile = repository
                .findProfile(key)
                .filter(value -> "ACTIVE".equals(value.state()))
                .orElseThrow(() -> ApiException.badRequest(
                        top.yealike.pep.model.contract.ModelErrorCodes.MODEL_NOT_ALLOWED, "模型未启用或不存在"));
        Provider provider = repository
                .findProviderById(profile.providerId())
                .filter(value -> "ACTIVE".equals(value.state()))
                .orElseThrow(() -> ApiException.unavailable(
                        top.yealike.pep.model.contract.ModelErrorCodes.MODEL_UNAVAILABLE, "模型供应商不可用"));
        String secret = cipher.open(provider.credentialCipher(), provider.credentialNonce());
        return new ResolvedModel(profile, provider, secret);
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9_.-]{2,64}")) {
            throw ApiException.badRequest("MODEL_REQUEST_INVALID", "编码须为 2 至 64 位字母、数字、点、下划线或短横线");
        }
    }

    private static void requireModelKey(String key) {
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw ApiException.badRequest("MODEL_REQUEST_INVALID", "逻辑模型 key 须为 2 至 40 位小写字母、数字或短横线");
        }
    }
}
