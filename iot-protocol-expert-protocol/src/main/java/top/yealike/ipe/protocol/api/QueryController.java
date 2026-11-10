package top.yealike.ipe.protocol.api;

import top.yealike.ipe.protocol.domain.DeviceEntity;
import top.yealike.ipe.protocol.domain.FrameLogEntity;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import top.yealike.ipe.protocol.service.DeviceService;
import top.yealike.ipe.protocol.service.FrameLogService;
import top.yealike.ipe.protocol.service.OrderService;
import top.yealike.ipe.protocol.service.TransactionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总览、订单、交易与帧流水查询。
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final DeviceService deviceService;
    private final OrderService orderService;
    private final TransactionService transactionService;
    private final FrameLogService frameLogService;
    private final SessionRegistry sessions;

    public QueryController(DeviceService deviceService, OrderService orderService,
                           TransactionService transactionService, FrameLogService frameLogService,
                           SessionRegistry sessions) {
        this.deviceService = deviceService;
        this.orderService = orderService;
        this.transactionService = transactionService;
        this.frameLogService = frameLogService;
        this.sessions = sessions;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<DeviceEntity> devices = deviceService.list();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceCount", devices.size());
        data.put("onlineCount", devices.stream().filter(DeviceEntity::isOnline).count());
        data.put("onlinePiles", sessions.onlinePiles());
        data.put("runningOrders", orderService.runningCount());
        data.put("inboundFrames", frameLogService.inboundCount());
        data.put("outboundFrames", frameLogService.outboundCount());
        data.put("orders", orderService.recent(10));
        data.put("transactions", transactionService.recent(10));
        data.put("frames", frameLogService.recent(20, null));
        data.put("serverTime", java.time.LocalDateTime.now());
        return data;
    }

    @GetMapping("/orders")
    public List<?> orders(@RequestParam(defaultValue = "50") int limit) {
        return orderService.recent(limit);
    }

    @GetMapping("/transactions")
    public List<?> transactions(@RequestParam(defaultValue = "50") int limit) {
        return transactionService.recent(limit);
    }

    @GetMapping("/frames")
    public List<FrameLogEntity> frames(@RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(required = false) String pileCode) {
        return frameLogService.recent(limit, pileCode);
    }
}