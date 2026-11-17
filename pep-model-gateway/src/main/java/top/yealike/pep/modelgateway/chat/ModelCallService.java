package top.yealike.pep.modelgateway.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.model.contract.ModelErrorCodes;
import top.yealike.pep.model.contract.ModelUsage;
import top.yealike.pep.model.contract.RunTokenPayload;
import top.yealike.pep.modelgateway.catalog.CatalogService;
import top.yealike.pep.modelgateway.quota.QuotaService;
import top.yealike.pep.modelgateway.support.ApiException;

@Service
public class ModelCallService {

    private final CatalogService catalog;
    private final QuotaService quota;
    private final TokenEstimator estimator;
    private final UsageNormalizer usageNormalizer;
    private final ObjectMapper json;

    public ModelCallService(
            CatalogService catalog,
            QuotaService quota,
            TokenEstimator estimator,
            UsageNormalizer usageNormalizer,
            ObjectMapper json) {
        this.catalog = catalog;
        this.quota = quota;
        this.estimator = estimator;
        this.usageNormalizer = usageNormalizer;
        this.json = json;
    }

    public record PreparedCall(
            CatalogService.ResolvedModel model,
            QuotaService.ReservationHandle reservation,
            String upstreamBody,
            boolean stream,
            int maxOutputTokens) {}

    public PreparedCall prepare(
            RunTokenPayload token, Map<String, Object> body, String logicalCallIdHeader, String attemptHeader) {
        String modelKey = body.get("model") == null ? token.modelProfile() : String.valueOf(body.get("model"));
        if (modelKey == null || modelKey.isBlank()) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "请求缺少 model");
        }
        if (token.modelProfile() != null
                && !token.modelProfile().isBlank()
                && !token.modelProfile().equals(modelKey)) {
            throw ApiException.badRequest(ModelErrorCodes.MODEL_NOT_ALLOWED, "运行凭据不允许该模型");
        }
        CatalogService.ResolvedModel model = catalog.resolve(modelKey);
        int requested = intValue(body.get("max_completion_tokens"), intValue(body.get("max_tokens"), 0));
        int maxOutput = requested <= 0
                ? model.profile().maxOutputTokens()
                : Math.min(requested, model.profile().maxOutputTokens());

        UUID logicalCallId = parseUuid(logicalCallIdHeader);
        int attemptNo = Math.max(1, intValue(attemptHeader, 1));
        String requestHash = sha256(json.writeValueAsString(body));
        long reserve = estimator.reserveUnits(
                body,
                maxOutput,
                model.profile().tokenWeightNum(),
                model.profile().tokenWeightDen());

        Map<String, Object> upstream = new LinkedHashMap<>(body);
        upstream.put("model", model.profile().upstreamModel());
        if (maxOutput > 0) {
            upstream.put("max_tokens", maxOutput);
            upstream.remove("max_completion_tokens");
        }
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        if (stream) {
            upstream.put("stream_options", Map.of("include_usage", true));
        }

        QuotaService.ReservationHandle reservation = quota.reserve(new QuotaService.ReservationRequest(
                logicalCallId,
                attemptNo,
                requestHash,
                token.scopeKind(),
                token.sub(),
                token.actor(),
                token.tenant(),
                token.project(),
                token.task(),
                token.run(),
                model.profile().key(),
                model.provider().code(),
                model.profile().upstreamModel(),
                reserve));
        quota.markDispatched(reservation.reservationId());
        return new PreparedCall(model, reservation, json.writeValueAsString(upstream), stream, maxOutput);
    }

    public void settle(PreparedCall call, Map<String, Object> usage, String providerRequestId, String raw) {
        ModelUsage normalized = usageNormalizer.normalize(
                usage,
                call.model().profile().tokenWeightNum(),
                call.model().profile().tokenWeightDen());
        if (normalized == null) {
            normalized = usageNormalizer.estimatedCap(call.reservation().reservedUnits());
        }
        quota.settle(call.reservation().reservationId(), normalized, providerRequestId, raw);
    }

    public void reject(PreparedCall call) {
        quota.releaseRejected(call.reservation().reservationId());
    }

    public void uncertain(PreparedCall call, String providerRequestId) {
        quota.markUncertain(call.reservation().reservationId(), providerRequestId);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest(ModelErrorCodes.REQUEST_INVALID, "调用 ID 必须是 UUID");
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
