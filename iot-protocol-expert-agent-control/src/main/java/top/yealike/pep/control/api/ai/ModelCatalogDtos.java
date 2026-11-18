package top.yealike.pep.control.api.ai;

public final class ModelCatalogDtos {

    private ModelCatalogDtos() {}

    public record ProviderCreate(String code, String name, String baseUrl, String apiKey) {}

    public record ModelCreate(
            String key,
            String displayName,
            String providerCode,
            String upstreamModel,
            Integer contextWindow,
            Integer maxOutputTokens,
            String countingMode) {}
}
