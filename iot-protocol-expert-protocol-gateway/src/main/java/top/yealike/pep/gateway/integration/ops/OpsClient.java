package top.yealike.pep.gateway.integration.ops;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.pep.gateway.config.properties.ProtocolProperties;
import top.yealike.pep.gateway.integration.ops.dto.AuthorizeResult;
import top.yealike.pep.gateway.integration.ops.dto.BillRequest;
import top.yealike.pep.gateway.integration.ops.dto.BillResult;
import top.yealike.pep.gateway.integration.ops.dto.BindOcppRequest;
import top.yealike.pep.gateway.integration.ops.dto.BindOcppResult;
import top.yealike.pep.gateway.integration.ops.dto.DeviceMappingPage;
import top.yealike.pep.gateway.integration.ops.dto.DevicePatch;
import top.yealike.pep.gateway.integration.ops.dto.DeviceView;
import top.yealike.pep.gateway.integration.ops.dto.FailRequest;
import top.yealike.pep.gateway.integration.ops.dto.FrameRequest;
import top.yealike.pep.gateway.integration.ops.dto.LoginRequest;
import top.yealike.pep.gateway.integration.ops.dto.MeterRequest;
import top.yealike.pep.gateway.integration.ops.dto.MeterValuesRequest;
import top.yealike.pep.gateway.integration.ops.dto.OrderView;
import top.yealike.pep.gateway.integration.ops.dto.RateModelView;
import top.yealike.pep.gateway.integration.ops.dto.RealtimeRequest;
import top.yealike.pep.gateway.integration.ops.dto.StartRequest;
import top.yealike.pep.gateway.integration.ops.dto.StartResult;
import top.yealike.pep.gateway.integration.ops.dto.StartTransactionRequest;
import top.yealike.pep.gateway.integration.ops.dto.StartTransactionResult;
import top.yealike.pep.gateway.integration.ops.dto.StatusRequest;
import top.yealike.pep.gateway.integration.ops.dto.StopRequest;
import top.yealike.pep.gateway.integration.ops.dto.StopTransactionRequest;
import top.yealike.pep.gateway.integration.ops.dto.StopTransactionResult;

/** 协议通道到运维平台的内部调用。通道不保存业务数据，所有业务判定都经此接口提交。 HTTP 调用在 Netty 业务线程池中执行，不占用 IO 事件循环。 */
@Component
public class OpsClient {

    private static final Logger log = LoggerFactory.getLogger(OpsClient.class);

    private final RestClient client;
    private final String token;
    private final Executor telemetryExecutor;
    private final MeterRegistry meters;
    private final AtomicLong lastRetentionWarning = new AtomicLong();

    public OpsClient(
            RestClient.Builder builder,
            ProtocolProperties properties,
            @Qualifier("frameRetentionExecutor") Executor telemetryExecutor,
            MeterRegistry meters) {
        this.token = properties.getOpsServiceToken();
        this.telemetryExecutor = telemetryExecutor;
        this.meters = meters;
        this.client = builder.baseUrl(properties.getOpsBaseUrl())
                .requestFactory(requestFactory(properties.getOpsTimeoutMs()))
                .build();
    }

    public DeviceView login(LoginRequest request) {
        return post("/internal/devices/login", request, DeviceView.class);
    }

    public int properties(String protocol, String deviceId, String channelNo, java.util.Map<String, Object> values) {
        Integer count = post(
                "/internal/properties",
                java.util.Map.of(
                        "protocol",
                        protocol,
                        "protocolDeviceId",
                        deviceId,
                        "channelNo",
                        channelNo,
                        "values",
                        values,
                        "collectedAt",
                        LocalDateTime.now().toString()),
                Integer.class);
        if (count == null || count != values.size()) {
            throw new IllegalStateException("平台未接受全部属性");
        }
        return count;
    }

    public DeviceView resolve(String protocol, String code) {
        return client.get()
                .uri(uri -> uri.path("/internal/devices/resolve")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(DeviceView.class);
    }

    public DeviceMappingPage deviceMappings(long afterId, int limit) {
        return client.get()
                .uri(uri -> uri.path("/internal/device-mappings")
                        .queryParam("afterId", Math.max(afterId, 0))
                        .queryParam("limit", Math.max(limit, 1))
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(DeviceMappingPage.class);
    }

    public void touch(String protocol, String code) {
        postNoBody("/internal/devices/touch?protocol={p}&protocolDeviceId={c}", protocol, code);
    }

    public void offline(String protocol, String code, String sessionId) {
        postNoBody(
                "/internal/devices/offline?protocol={p}&protocolDeviceId={c}&sessionId={s}",
                protocol,
                code,
                sessionId == null ? "" : sessionId);
    }

    public DeviceView patch(String protocol, String code, DevicePatch patch) {
        return client.patch()
                .uri(uri -> uri.path("/internal/devices/patch")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .build())
                .header("X-Ops-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(patch)
                .retrieve()
                .body(DeviceView.class);
    }

    public StartResult start(StartRequest request) {
        return post("/internal/orders/start", request, StartResult.class);
    }

    public OrderView active(String protocol, String code, String gunNo) {
        return client.get()
                .uri(uri -> uri.path("/internal/orders/active")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .queryParam("gunNo", gunNo)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OrderView.class);
    }

    public OrderView byNo(String orderNo) {
        return client.get()
                .uri("/internal/orders/by-no/{orderNo}", orderNo)
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OrderView.class);
    }

    public OrderView byOcpp(String protocol, String code, Integer transactionId) {
        return client.get()
                .uri(uri -> uri.path("/internal/orders/by-ocpp")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .queryParam("transactionId", transactionId)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OrderView.class);
    }

    public int bindOcpp(String orderNo, BigDecimal meterStartWh) {
        BindOcppResult result = post(
                "/internal/orders/{orderNo}/bind-ocpp",
                new BindOcppRequest(meterStartWh),
                BindOcppResult.class,
                orderNo);
        return result == null ? 0 : result.transactionId();
    }

    public void meter(String orderNo, BigDecimal energy, BigDecimal amount) {
        postBody("/internal/orders/{orderNo}/meter", new MeterRequest(energy, amount), orderNo);
    }

    public void charging(String orderNo, LocalDateTime startTime) {
        client.post()
                .uri(uri -> uri.path("/internal/orders/{orderNo}/charging")
                        .queryParam("startTime", startTime == null ? "" : startTime.toString())
                        .build(orderNo))
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .toBodilessEntity();
    }

    public void chargingByTransaction(
            String protocol, String protocolDeviceId, String transactionNo, LocalDateTime startTime) {
        client.post()
                .uri(uri -> {
                    var builder = uri.path("/internal/orders/by-transaction/{transactionNo}/charging")
                            .queryParam("protocol", protocol)
                            .queryParam("protocolDeviceId", protocolDeviceId);
                    if (startTime != null) {
                        builder.queryParam("startTime", startTime);
                    }
                    return builder.build(transactionNo);
                })
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .toBodilessEntity();
    }

    public void stop(String orderNo, int reason) {
        postBody("/internal/orders/{orderNo}/stop", new StopRequest(reason), orderNo);
    }

    public void fail(String orderNo, String reason) {
        postBody("/internal/orders/{orderNo}/fail", new FailRequest(reason), orderNo);
    }

    public void failByTransaction(String protocol, String protocolDeviceId, String transactionNo, String reason) {
        client.post()
                .uri(uri -> uri.path("/internal/orders/by-transaction/{transactionNo}/fail")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", protocolDeviceId)
                        .build(transactionNo))
                .header("X-Ops-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FailRequest(reason))
                .retrieve()
                .toBodilessEntity();
    }

    public BillResult acceptYkc(BillRequest request) {
        return post("/internal/bills/ykc", request, BillResult.class);
    }

    public void gunState(RealtimeRequest request) {
        postBody("/internal/gun-states", request);
    }

    public void frame(FrameRequest request) {
        // Frame retention is diagnostic side data. It must not block protocol I/O or consume
        // the command deadline when the OPS database is slow or unavailable.
        try {
            telemetryExecutor.execute(() -> {
                try {
                    postBody("/internal/frames", request);
                } catch (RuntimeException e) {
                    meters.counter("ipe.protocol.frame.retention.failed").increment();
                    warnRateLimited("报文留存失败 {} {}: {}", request.protocol(), request.direction(), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            meters.counter("ipe.protocol.frame.retention.dropped").increment();
            warnRateLimited("报文留存队列已满，丢弃诊断帧 {} {}", request.protocol(), request.direction());
        }
    }

    public RateModelView activeRateModel() {
        return client.get()
                .uri("/internal/rate-models/active")
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(RateModelView.class);
    }

    public boolean verifyRateModel(String modelNo) {
        Boolean result = client.get()
                .uri(uri -> uri.path("/internal/rate-models/verify")
                        .queryParam("modelNo", modelNo)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public DeviceView bootOcpp(LoginRequest request) {
        return post("/internal/ocpp/boot", request, DeviceView.class);
    }

    public void ocppHeartbeat(String code) {
        postNoBody("/internal/ocpp/heartbeat?protocolDeviceId={c}", code);
    }

    public void ocppStatus(StatusRequest request) {
        postBody("/internal/ocpp/status", request);
    }

    public String authorize(String idTag) {
        AuthorizeResult result =
                post("/internal/ocpp/authorize", java.util.Map.of("idTag", idTag), AuthorizeResult.class);
        return result == null ? "Rejected" : result.status();
    }

    public StartTransactionResult startTransaction(StartTransactionRequest request) {
        return post("/internal/ocpp/start-transaction", request, StartTransactionResult.class);
    }

    public void meterValues(MeterValuesRequest request) {
        postBody("/internal/ocpp/meter-values", request);
    }

    public StopTransactionResult stopTransaction(StopTransactionRequest request) {
        return post("/internal/ocpp/stop-transaction", request, StopTransactionResult.class);
    }

    private <T> T post(String path, Object body, Class<T> type, Object... uriVariables) {
        return client.post()
                .uri(path, uriVariables)
                .header("X-Ops-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(type);
    }

    private void postNoBody(String path, Object... uriVariables) {
        client.post()
                .uri(path, uriVariables)
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .toBodilessEntity();
    }

    private void postBody(String path, Object body, Object... uriVariables) {
        client.post()
                .uri(path, uriVariables)
                .header("X-Ops-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(int timeoutMs) {
        var settings = org.springframework.boot.http.client.HttpClientSettings.defaults()
                .withTimeouts(Duration.ofMillis(Math.max(timeoutMs, 500)), Duration.ofMillis(Math.max(timeoutMs, 500)));
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }

    private void warnRateLimited(String pattern, Object... arguments) {
        long now = System.nanoTime();
        long previous = lastRetentionWarning.get();
        if (now - previous >= Duration.ofSeconds(30).toNanos() && lastRetentionWarning.compareAndSet(previous, now)) {
            log.warn(pattern, arguments);
        }
    }
}
