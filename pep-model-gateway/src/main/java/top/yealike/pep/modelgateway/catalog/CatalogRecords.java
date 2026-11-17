package top.yealike.pep.modelgateway.catalog;

import java.util.UUID;

public final class CatalogRecords {

    private CatalogRecords() {}

    public record Provider(
            UUID id,
            String code,
            String name,
            String baseUrl,
            byte[] credentialCipher,
            byte[] credentialNonce,
            String state) {}

    public record ModelProfile(
            UUID id,
            String key,
            String displayName,
            UUID providerId,
            String upstreamModel,
            Integer contextWindow,
            int maxOutputTokens,
            int tokenWeightNum,
            int tokenWeightDen,
            String countingMode,
            String state) {}
}
