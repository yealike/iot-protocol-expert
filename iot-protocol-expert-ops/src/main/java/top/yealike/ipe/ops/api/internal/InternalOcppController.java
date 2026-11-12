package top.yealike.ipe.ops.api.internal;

import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.service.OcppBusinessService;

@RestController
@RequestMapping("/internal/ocpp")
public class InternalOcppController {

    private final OcppBusinessService ocpp;

    public InternalOcppController(OcppBusinessService ocpp) {
        this.ocpp = ocpp;
    }

    @PostMapping("/boot")
    public OpsDtos.DeviceView boot(@RequestBody OpsDtos.LoginRequest request) {
        return ocpp.boot(request);
    }

    @PostMapping("/heartbeat")
    public void heartbeat(@RequestParam String protocolDeviceId) {
        ocpp.heartbeat(protocolDeviceId);
    }

    @PostMapping("/status")
    public void status(@RequestBody OpsDtos.StatusRequest request) {
        ocpp.status(request);
    }

    @PostMapping("/authorize")
    public OpsDtos.AuthorizeResult authorize(@RequestBody java.util.Map<String, String> body) {
        return ocpp.authorize(body.get("idTag"));
    }

    @PostMapping("/start-transaction")
    public OpsDtos.StartTransactionResult startTransaction(@RequestBody OpsDtos.StartTransactionRequest request) {
        return ocpp.startTransaction(request);
    }

    @PostMapping("/meter-values")
    public void meterValues(@RequestBody OpsDtos.MeterValuesRequest request) {
        ocpp.meterValues(request);
    }

    @PostMapping("/stop-transaction")
    public OpsDtos.StopTransactionResult stopTransaction(@RequestBody OpsDtos.StopTransactionRequest request) {
        return ocpp.stopTransaction(request);
    }
}
