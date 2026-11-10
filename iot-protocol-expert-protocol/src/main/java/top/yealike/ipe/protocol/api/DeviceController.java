package top.yealike.ipe.protocol.api;

import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.service.ChargeService;
import top.yealike.ipe.protocol.service.CommandFacade;
import top.yealike.ipe.protocol.service.DeviceService;
import top.yealike.ipe.protocol.service.GunStateService;
import top.yealike.ipe.protocol.domain.DeviceEntity;
import top.yealike.ipe.protocol.domain.GunStateEntity;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 设备查询与下行命令。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final GunStateService gunStateService;
    private final ChargeService chargeService;
    private final CommandFacade commands;
    private final SessionRegistry sessions;

    public DeviceController(DeviceService deviceService, GunStateService gunStateService,
                            ChargeService chargeService, CommandFacade commands,
                            SessionRegistry sessions) {
        this.deviceService = deviceService;
        this.gunStateService = gunStateService;
        this.chargeService = chargeService;
        this.commands = commands;
        this.sessions = sessions;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return deviceService.list().stream().map(this::view).toList();
    }

    @GetMapping("/{pileCode}")
    public Map<String, Object> detail(@PathVariable String pileCode) {
        DeviceEntity device = deviceService.find(pileCode);
        return Map.of("device", view(device),
                "guns", gunStateService.byPile(pileCode),
                "online", sessions.isOnline(pileCode));
    }

    @GetMapping("/{pileCode}/guns")
    public List<GunStateEntity> guns(@PathVariable String pileCode) {
        return gunStateService.byPile(pileCode);
    }

    @PostMapping("/{pileCode}/start")
    public ChargeService.CommandResult start(@PathVariable String pileCode, @RequestBody StartRequest request) {
        return chargeService.remoteStart(pileCode, request.gunNo(), request.logicalCard(),
                request.physicalCard(), request.balance());
    }

    @PostMapping("/{pileCode}/parallel-start")
    public Object parallel(@PathVariable String pileCode) {return chargeService.remoteParallelStart(pileCode,null);}

    @PostMapping("/{pileCode}/stop")
    public ChargeService.CommandResult stop(@PathVariable String pileCode, @RequestBody StopRequest request) {
        return chargeService.remoteStop(pileCode, request.gunNo());
    }

    @PostMapping("/{pileCode}/read-realtime")
    public Object readRealtime(@PathVariable String pileCode, @RequestBody GunRequest request) {
        return commands.readRealtime(pileCode, request.gunNo());
    }

    @PostMapping("/{pileCode}/time-sync")
    public Object timeSync(@PathVariable String pileCode) {
        return commands.timeSync(pileCode);
    }

    @PostMapping("/{pileCode}/param-set")
    public Object paramSet(@PathVariable String pileCode, @RequestBody ParamSetRequest request) {
        return commands.paramSet(pileCode, request.allowWork(), request.maxPowerPercent());
    }

    @PostMapping("/{pileCode}/reboot")
    public Object reboot(@PathVariable String pileCode, @RequestBody(required = false) RebootRequest request) {
        return commands.reboot(pileCode, request == null ? 1 : request.control());
    }

    @PostMapping("/{pileCode}/update")
    public Object update(@PathVariable String pileCode, @RequestBody UpdateRequest request) {
        return commands.update(pileCode, new MaintenanceFrames.Update(pileCode, request.pileModel(),
                request.power(), request.serverAddress(), request.port(), request.username(),
                request.password(), request.filePath(), request.control(), request.downloadTimeoutMinutes()));
    }

    @PostMapping("/{pileCode}/rate-model")
    public Object pushRateModel(@PathVariable String pileCode) {
        return commands.pushRateModel(pileCode);
    }

    @PostMapping("/{pileCode}/balance")
    public Object balance(@PathVariable String pileCode, @RequestBody BalanceRequest request) {
        return commands.balance(pileCode, request.gunNo(), request.physicalCard(), request.balance());
    }

    @PostMapping("/{pileCode}/cards/sync")
    public Object cardSync(@PathVariable String pileCode, @RequestBody CardsRequest request) {
        return commands.cardSync(pileCode, request.cards().stream()
                .map(c -> new top.yealike.ipe.protocol.codec.frames.OperationFrames.OfflineCard(
                        c.logicalCard(), c.physicalCard())).toList());
    }

    @PostMapping("/{pileCode}/cards/clear")
    public Object cardClear(@PathVariable String pileCode, @RequestBody CardNumbersRequest request) {
        return commands.cardClear(pileCode, request.physicalCards());
    }

    @PostMapping("/{pileCode}/cards/query")
    public Object cardQuery(@PathVariable String pileCode, @RequestBody CardNumbersRequest request) {
        return commands.cardQuery(pileCode, request.physicalCards());
    }

    @PostMapping("/{pileCode}/lock")
    public Object lock(@PathVariable String pileCode, @RequestBody LockRequest request) {
        return commands.lockControl(pileCode, request.gunNo(), request.action());
    }

    private Map<String, Object> view(DeviceEntity d) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("pileCode", d.getPileCode());
        view.put("pileType", d.getPileType());
        view.put("gunCount", d.getGunCount());
        view.put("protocolVersion", d.getProtocolVersion());
        view.put("programVersion", d.getProgramVersion() == null ? "" : d.getProgramVersion());
        view.put("online", d.isOnline());
        view.put("allowWork", d.isAllowWork());
        view.put("maxPowerPercent", d.getMaxPowerPercent());
        view.put("stationName", d.getStationName() == null ? "" : d.getStationName());
        view.put("lastSeen", d.getLastSeen());
        view.put("registeredAt", d.getRegisteredAt());
        return view;
    }

    public record StartRequest(String gunNo, String logicalCard, String physicalCard, BigDecimal balance) {
        public StartRequest {
            gunNo = gunNo == null || gunNo.isBlank() ? "01" : gunNo;
            logicalCard = logicalCard == null ? "00000000" : logicalCard;
            physicalCard = physicalCard == null ? "0000000000000000" : physicalCard;
        }
    }

    public record StopRequest(String gunNo) {
        public StopRequest {
            gunNo = gunNo == null || gunNo.isBlank() ? "01" : gunNo;
        }
    }

    public record GunRequest(String gunNo) {
        public GunRequest {
            gunNo = gunNo == null || gunNo.isBlank() ? "01" : gunNo;
        }
    }

    public record ParamSetRequest(boolean allowWork, int maxPowerPercent) {
    }

    public record RebootRequest(int control) {
    }

    public record UpdateRequest(int pileModel, int power, String serverAddress, int port,
                                String username, String password, String filePath,
                                int control, int downloadTimeoutMinutes) {
    }

    public record BalanceRequest(String gunNo, String physicalCard, BigDecimal balance) {
        public BalanceRequest {
            gunNo = gunNo == null || gunNo.isBlank() ? "01" : gunNo;
            physicalCard = physicalCard == null ? "0000000000000000" : physicalCard;
            balance = balance == null ? BigDecimal.ZERO : balance;
        }
    }

    public record CardItem(String logicalCard, String physicalCard) {
    }

    public record CardsRequest(List<CardItem> cards) {
    }

    public record CardNumbersRequest(List<String> physicalCards) {
    }

    public record LockRequest(int gunNo, int action) {
        public LockRequest {
            gunNo = gunNo <= 0 ? 1 : gunNo;
        }
    }
}