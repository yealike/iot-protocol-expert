package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import top.yealike.pep.ops.api.internal.dto.AuthorizeResult;
import top.yealike.pep.ops.api.internal.dto.DeviceView;
import top.yealike.pep.ops.api.internal.dto.LoginRequest;
import top.yealike.pep.ops.api.internal.dto.MeterValuesRequest;
import top.yealike.pep.ops.api.internal.dto.StartTransactionRequest;
import top.yealike.pep.ops.api.internal.dto.StartTransactionResult;
import top.yealike.pep.ops.api.internal.dto.StatusRequest;
import top.yealike.pep.ops.api.internal.dto.StopTransactionRequest;
import top.yealike.pep.ops.api.internal.dto.StopTransactionResult;
import top.yealike.pep.ops.charging.service.OcppBusinessService;

@RestController
@RequestMapping(path = "/internal/ocpp", produces = "application/json")
@Tag(name = "内部 OCPP", description = "OCPP 1.6J 通道上报的启动、计量与交易状态同步")
@SecurityRequirement(name = "opsServiceToken")
public class InternalOcppController {

    private final OcppBusinessService ocpp;

    public InternalOcppController(OcppBusinessService ocpp) {
        this.ocpp = ocpp;
    }

    @PostMapping("/boot")
    @Operation(operationId = "internalOcppBoot", summary = "OCPP 设备上线")
    public DeviceView boot(@RequestBody LoginRequest request) {
        return ocpp.boot(request);
    }

    @PostMapping("/heartbeat")
    @Operation(operationId = "internalOcppHeartbeat", summary = "OCPP 心跳")
    public void heartbeat(@Parameter(description = "OCPP chargePointId") @RequestParam String protocolDeviceId) {
        ocpp.heartbeat(protocolDeviceId);
    }

    @PostMapping("/status")
    @Operation(operationId = "internalOcppStatus", summary = "同步枪口状态")
    public void status(@RequestBody StatusRequest request) {
        ocpp.status(request);
    }

    @PostMapping("/authorize")
    @Operation(operationId = "internalOcppAuthorize", summary = "校验本地卡授权")
    public AuthorizeResult authorize(@RequestBody java.util.Map<String, String> body) {
        return ocpp.authorize(body.get("idTag"));
    }

    @PostMapping("/start-transaction")
    @Operation(operationId = "internalOcppStartTransaction", summary = "开始充电交易")
    public StartTransactionResult startTransaction(@RequestBody StartTransactionRequest request) {
        return ocpp.startTransaction(request);
    }

    @PostMapping("/meter-values")
    @Operation(operationId = "internalOcppMeterValues", summary = "上报电表采样")
    public void meterValues(@RequestBody MeterValuesRequest request) {
        ocpp.meterValues(request);
    }

    @PostMapping("/stop-transaction")
    @Operation(operationId = "internalOcppStopTransaction", summary = "结束充电交易")
    public StopTransactionResult stopTransaction(@RequestBody StopTransactionRequest request) {
        return ocpp.stopTransaction(request);
    }
}
