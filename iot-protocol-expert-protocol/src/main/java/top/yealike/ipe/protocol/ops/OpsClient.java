package top.yealike.ipe.protocol.ops;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.protocol.config.ProtocolProperties;

/** 协议通道到运维平台的内部调用。通道不保存业务数据，所有业务判定都经此接口提交。 HTTP 调用在 Netty 业务线程池中执行，不占用 IO 事件循环。 */
@Component
public class OpsClient {

    private static final Logger log = LoggerFactory.getLogger(OpsClient.class);

    private final RestClient client;
    private final String token;

    public OpsClient(RestClient.Builder builder, ProtocolProperties properties) {
        this.token = properties.getOpsServiceToken();
        this.client = builder.baseUrl(properties.getOpsBaseUrl())
                .requestFactory(requestFactory(properties.getOpsTimeoutMs()))
                .build();
    }

    public OpsDtos.DeviceView login(OpsDtos.LoginRequest request) {
        return post("/internal/devices/login", request, OpsDtos.DeviceView.class);
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

    public OpsDtos.DeviceView resolve(String protocol, String code) {
        return client.get()
                .uri(uri -> uri.path("/internal/devices/resolve")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OpsDtos.DeviceView.class);
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

    public OpsDtos.DeviceView patch(String protocol, String code, OpsDtos.DevicePatch patch) {
        return client.patch()
                .uri(uri -> uri.path("/internal/devices/patch")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .build())
                .header("X-Ops-Service-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(patch)
                .retrieve()
                .body(OpsDtos.DeviceView.class);
    }

    public OpsDtos.StartResult start(OpsDtos.StartRequest request) {
        return post("/internal/orders/start", request, OpsDtos.StartResult.class);
    }

    public OpsDtos.OrderView active(String protocol, String code, String gunNo) {
        return client.get()
                .uri(uri -> uri.path("/internal/orders/active")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .queryParam("gunNo", gunNo)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OpsDtos.OrderView.class);
    }

    public OpsDtos.OrderView byNo(String orderNo) {
        return client.get()
                .uri("/internal/orders/by-no/{orderNo}", orderNo)
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OpsDtos.OrderView.class);
    }

    public OpsDtos.OrderView byOcpp(String protocol, String code, Integer transactionId) {
        return client.get()
                .uri(uri -> uri.path("/internal/orders/by-ocpp")
                        .queryParam("protocol", protocol)
                        .queryParam("protocolDeviceId", code)
                        .queryParam("transactionId", transactionId)
                        .build())
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OpsDtos.OrderView.class);
    }

    public int bindOcpp(String orderNo, BigDecimal meterStartWh) {
        OpsDtos.BindOcppResult result = post(
                "/internal/orders/{orderNo}/bind-ocpp",
                new OpsDtos.BindOcppRequest(meterStartWh),
                OpsDtos.BindOcppResult.class,
                orderNo);
        return result == null ? 0 : result.transactionId();
    }

    public void meter(String orderNo, BigDecimal energy, BigDecimal amount) {
        postBody("/internal/orders/{orderNo}/meter", new OpsDtos.MeterRequest(energy, amount), orderNo);
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

    public void stop(String orderNo, int reason) {
        postBody("/internal/orders/{orderNo}/stop", new OpsDtos.StopRequest(reason), orderNo);
    }

    public void fail(String orderNo, String reason) {
        postBody("/internal/orders/{orderNo}/fail", new OpsDtos.FailRequest(reason), orderNo);
    }

    public OpsDtos.BillResult acceptYkc(OpsDtos.BillRequest request) {
        return post("/internal/bills/ykc", request, OpsDtos.BillResult.class);
    }

    public void gunState(OpsDtos.RealtimeRequest request) {
        postBody("/internal/gun-states", request);
    }

    public void frame(OpsDtos.FrameRequest request) {
        try {
            postBody("/internal/frames", request);
        } catch (RuntimeException e) {
            log.warn("报文留存失败 {} {}: {}", request.protocol(), request.direction(), e.getMessage());
        }
    }

    public OpsDtos.RateModelView activeRateModel() {
        return client.get()
                .uri("/internal/rate-models/active")
                .header("X-Ops-Service-Token", token)
                .retrieve()
                .body(OpsDtos.RateModelView.class);
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

    public OpsDtos.DeviceView bootOcpp(OpsDtos.LoginRequest request) {
        return post("/internal/ocpp/boot", request, OpsDtos.DeviceView.class);
    }

    public void ocppHeartbeat(String code) {
        postNoBody("/internal/ocpp/heartbeat?protocolDeviceId={c}", code);
    }

    public void ocppStatus(OpsDtos.StatusRequest request) {
        postBody("/internal/ocpp/status", request);
    }

    public String authorize(String idTag) {
        OpsDtos.AuthorizeResult result =
                post("/internal/ocpp/authorize", java.util.Map.of("idTag", idTag), OpsDtos.AuthorizeResult.class);
        return result == null ? "Rejected" : result.status();
    }

    public OpsDtos.StartTransactionResult startTransaction(OpsDtos.StartTransactionRequest request) {
        return post("/internal/ocpp/start-transaction", request, OpsDtos.StartTransactionResult.class);
    }

    public void meterValues(OpsDtos.MeterValuesRequest request) {
        postBody("/internal/ocpp/meter-values", request);
    }

    public OpsDtos.StopTransactionResult stopTransaction(OpsDtos.StopTransactionRequest request) {
        return post("/internal/ocpp/stop-transaction", request, OpsDtos.StopTransactionResult.class);
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
}
