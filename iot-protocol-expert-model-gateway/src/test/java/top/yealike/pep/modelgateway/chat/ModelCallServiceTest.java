package top.yealike.pep.modelgateway.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.model.contract.ModelErrorCodes;
import top.yealike.pep.model.contract.RunTokenPayload;
import top.yealike.pep.modelgateway.catalog.CatalogService;
import top.yealike.pep.modelgateway.catalog.domain.ModelProfile;
import top.yealike.pep.modelgateway.catalog.domain.Provider;
import top.yealike.pep.modelgateway.catalog.domain.ResolvedModel;
import top.yealike.pep.modelgateway.quota.QuotaService;
import top.yealike.pep.modelgateway.quota.domain.ReservationHandle;
import top.yealike.pep.modelgateway.support.ApiException;

class ModelCallServiceTest {

    @Test
    void replayedLogicalCallFailsBeforeProviderDispatch() {
        var catalog = mock(CatalogService.class);
        var quota = mock(QuotaService.class);
        var estimator = mock(TokenEstimator.class);
        var normalizer = mock(UsageNormalizer.class);
        var token = new RunTokenPayload(
                "model-gateway",
                "user-1",
                "USER",
                "user-1",
                "tenant-1",
                "project-1",
                null,
                "run-1",
                "test-model",
                Long.MAX_VALUE,
                "token-1");
        var profile = new ModelProfile(
                UUID.randomUUID(),
                "test-model",
                "测试模型",
                UUID.randomUUID(),
                "upstream-model",
                8192,
                1024,
                1,
                1,
                "ESTIMATE",
                "ACTIVE");
        var provider = new Provider(
                UUID.randomUUID(), "test-provider", "测试供应商", "http://127.0.0.1", new byte[0], new byte[0], "ACTIVE");
        when(catalog.resolve("test-model")).thenReturn(new ResolvedModel(profile, provider, "secret"));
        when(estimator.reserveUnits(anyMap(), anyInt(), anyInt(), anyInt())).thenReturn(10L);
        when(quota.reserve(any()))
                .thenReturn(new ReservationHandle(UUID.randomUUID(), UUID.randomUUID(), 10L, List.of(), true));
        var service = new ModelCallService(
                catalog, quota, estimator, normalizer, JsonMapper.builder().build());

        assertThatThrownBy(() -> service.prepare(
                        token, Map.of("model", "test-model"), UUID.randomUUID().toString(), "1"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.status())
                            .isEqualTo(409);
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(ModelErrorCodes.CALL_CONFLICT);
                });
        verify(quota, never()).markDispatched(any());
    }

    @Test
    void invalidReservationBlocksProviderDispatch() {
        var catalog = mock(CatalogService.class);
        var quota = mock(QuotaService.class);
        var estimator = mock(TokenEstimator.class);
        var normalizer = mock(UsageNormalizer.class);
        var token = new RunTokenPayload(
                "model-gateway",
                "user-1",
                "USER",
                "user-1",
                "tenant-1",
                "project-1",
                null,
                "run-1",
                "test-model",
                Long.MAX_VALUE,
                "token-1");
        var profile = new ModelProfile(
                UUID.randomUUID(),
                "test-model",
                "测试模型",
                UUID.randomUUID(),
                "upstream-model",
                8192,
                1024,
                1,
                1,
                "ESTIMATE",
                "ACTIVE");
        var provider = new Provider(
                UUID.randomUUID(), "test-provider", "测试供应商", "http://127.0.0.1", new byte[0], new byte[0], "ACTIVE");
        when(catalog.resolve("test-model")).thenReturn(new ResolvedModel(profile, provider, "secret"));
        when(estimator.reserveUnits(anyMap(), anyInt(), anyInt(), anyInt())).thenReturn(10L);
        when(quota.reserve(any()))
                .thenReturn(new ReservationHandle(UUID.randomUUID(), UUID.randomUUID(), 10L, List.of(), false));
        when(quota.markDispatched(any())).thenReturn(false);
        var service = new ModelCallService(
                catalog, quota, estimator, normalizer, JsonMapper.builder().build());

        assertThatThrownBy(() -> service.prepare(
                        token, Map.of("model", "test-model"), UUID.randomUUID().toString(), "1"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.status())
                            .isEqualTo(409);
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(ModelErrorCodes.QUOTA_PENDING_RECONCILIATION);
                });
        verify(quota).markDispatched(any());
    }
}
