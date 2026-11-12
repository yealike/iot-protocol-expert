package top.yealike.ipe.simulator.runtime;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameCodec;
import top.yealike.ipe.protocol.codec.FrameStreamDecoder;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.ProtocolException;
import top.yealike.ipe.protocol.codec.TripleDes;
import top.yealike.ipe.protocol.codec.frames.BmsFrames;
import top.yealike.ipe.protocol.codec.frames.MaintenanceFrames;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.codec.frames.ParallelFrames;
import top.yealike.ipe.protocol.codec.frames.RealtimeFrames;
import top.yealike.ipe.protocol.codec.frames.RegistrationFrames;
import top.yealike.ipe.protocol.codec.frames.SettingFrames;
import top.yealike.ipe.protocol.telemetry.ProtocolTelemetry;
import top.yealike.ipe.simulator.config.SimulatorProperties;

/** 充电桩状态机与云快充 TCP 客户端。 */
@Component
public class SimulatorRuntime {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRuntime.class);

    /** 模拟器当前使用的充电协议。 */
    public enum Protocol {
        YKC16("ykc16", "云快充 1.6"),
        OCPP16J("ocpp16j", "OCPP 1.6J");

        private final String code;
        private final String label;

        Protocol(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }

        public static Protocol of(String value) {
            if (value == null || value.isBlank()) {
                return YKC16;
            }
            for (Protocol protocol : values()) {
                if (protocol.code.equalsIgnoreCase(value.trim())
                        || protocol.name().equalsIgnoreCase(value.trim())) {
                    return protocol;
                }
            }
            throw new IllegalArgumentException("未知协议: " + value);
        }
    }

    public enum GunState {
        OFFLINE(0, "离线"),
        FAULT(1, "故障"),
        IDLE(2, "空闲"),
        CHARGING(3, "充电");

        private final int code;
        private final String label;

        GunState(int code, String label) {
            this.code = code;
            this.label = label;
        }

        public int code() {
            return code;
        }

        public String label() {
            return label;
        }
    }

    public static final class Gun {
        private final String gunNo;
        private boolean plugged = false;
        private String phase = "待机";
        private ChargingLedger ledger;
        private LocalDateTime lastAdvance;
        private BigDecimal meter = new BigDecimal("1000.0000"), meterStart = meter;
        private String physicalCard = "00000000D14B0A54";
        private BigDecimal balance = new BigDecimal("1000");
        private int startType = 1, initialSoc = 20;
        private double capacityKwh = 60, socValue = 20;
        private String vin = "LSVAA2A40NX000001";
        private String parallelNo;
        private int mainGun;
        private long lastBmsAt;
        private long lastLockAt;
        private long authDeadline;
        private double targetEnergy, targetAmount;
        private int targetMinutes;
        private int bmsErrorReason;
        private int lockStatus = 255;
        private OperationFrames.RemoteStart waitingStart;
        private Integer ocppTransactionId;
        private int waitingSequence;
        private long waitingUntil;
        private boolean fault;
        private boolean allowWork = true;
        private GunState state = GunState.IDLE;
        private String transactionNo;
        private LocalDateTime startTime;
        private BigDecimal energy = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private int soc = 20;
        private BigDecimal voltage = BigDecimal.ZERO;
        private BigDecimal current = BigDecimal.ZERO;
        private int totalMinutes;
        private int remainMinutes;
        private long lastRealtimeAt;
        private long lastHeartbeatAt;
        private long heartbeatAckAt;

        Gun(String gunNo) {
            this.gunNo = gunNo;
        }

        public String getGunNo() {
            return gunNo;
        }

        public boolean isPlugged() {
            return plugged;
        }

        public boolean isFault() {
            return fault;
        }

        public boolean isAllowWork() {
            return allowWork;
        }

        public GunState getState() {
            return state;
        }

        public String getTransactionNo() {
            return transactionNo;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public BigDecimal getEnergy() {
            return energy;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public int getSoc() {
            return soc;
        }

        public BigDecimal getVoltage() {
            return voltage;
        }

        public BigDecimal getCurrent() {
            return current;
        }

        public int getTotalMinutes() {
            return totalMinutes;
        }

        public int getRemainMinutes() {
            return remainMinutes;
        }
    }

    public record FrameLog(
            LocalDateTime time,
            String direction,
            String label,
            String frameTypeHex,
            int sequence,
            String bodyHex,
            String summary) {
        public Object getDecoded() {
            if (frameTypeHex != null && !frameTypeHex.startsWith("0x")) {
                return Map.of(
                        "protocol", "OCPP16J", "messageType", frameTypeHex, "payload", bodyHex == null ? "" : bodyHex);
            }
            try {
                return top.yealike.ipe.protocol.codec.FramePayloads.decode(
                        FrameType.of(Integer.decode(frameTypeHex)), FrameCodec.parseHex(bodyHex));
            } catch (RuntimeException e) {
                return Map.of("error", e.getMessage());
            }
        }
    }

    private final SimulatorProperties properties;
    private final ProtocolTelemetry telemetry;
    private final OcppSimulatorClient ocppClient;
    private final byte[] key;
    private final Map<String, Gun> guns = new LinkedHashMap<>();
    private final Deque<FrameLog> logs = new ArrayDeque<>();
    private final Map<String, PendingTransaction> pendingTransactions = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final EventLoopGroup group = new NioEventLoopGroup(2);

    private volatile Protocol protocol = Protocol.YKC16;
    private volatile Channel channel;
    private volatile boolean connected;
    private volatile boolean loggedIn;
    private volatile boolean manualDisconnect;
    private volatile long nextReconnectAt;
    private volatile RegistrationFrames.RateModel rateModel;
    private volatile String lastError;
    private boolean rateReady;
    private long loginSentAt, rateRequestedAt;
    private final Map<String, OperationFrames.OfflineCard> offlineCards = new ConcurrentHashMap<>();
    private final List<OperationFrames.Transaction> history = new ArrayList<>();
    private LocalDateTime simulatedTime = LocalDateTime.now();
    private long clockNanos = System.nanoTime();
    private double speed = 1;
    private int maxPowerPercent = 100;
    private boolean rebootWhenIdle;
    private String upgradeStatus = "未升级";
    private int upgradeResult;
    private MaintenanceFrames.Update pendingUpdate;
    private int updateSequence;
    private final Map<String, ParallelFrames.ParallelStartAck> parallelAcks = new LinkedHashMap<>();
    private final Map<String, ParallelFrames.ParallelRemoteStart> parallelCommands = new LinkedHashMap<>();
    private String pendingParallelNo;
    private long parallelDeadline;

    @Autowired(required = false)
    private OpsDeviceClient opsDevices;

    private Long selectedDeviceId;
    private String selectedDeviceSn;

    public SimulatorRuntime(SimulatorProperties properties, ProtocolTelemetry telemetry) {
        this.telemetry = telemetry;
        this.properties = properties;
        this.key = TripleDes.keyFromHex(properties.getCryptoKeyHex());
        this.ocppClient = new OcppSimulatorClient(properties, this);
    }

    @PostConstruct
    public void init() {
        if (!properties.getPileCode().matches("\\d{14}")
                || properties.getGunCount() < 1
                || properties.getGunCount() > 99) throw new IllegalArgumentException("桩号须14位数字，枪数1~99");
        if (!Double.isFinite(properties.getRatedVoltage())
                || properties.getRatedVoltage() < 1
                || properties.getRatedVoltage() > 1000
                || !Double.isFinite(properties.getRatedCurrent())
                || properties.getRatedCurrent() <= 0
                || properties.getRatedCurrent() > 400) throw new IllegalArgumentException("额定电压1~1000V，电流0~400A");
        protocol = Protocol.of(properties.getProtocol());
        rateModel = defaultRateModel(properties.getPileCode());
        for (int i = 1; i <= Math.max(properties.getGunCount(), 1); i++) {
            guns.put(String.format("%02d", i), new Gun(String.format("%02d", i)));
        }
        // Hold reconnects until Spring's tracing and logging listeners are ready.
        manualDisconnect = true;
        restore();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.getAutoSelectDeviceId() != null) {
            try {
                selectDevice(properties.getAutoSelectDeviceId());
            } catch (RuntimeException e) {
                lastError = "自动选桩失败: " + e.getMessage();
                log.warn(lastError);
            }
            return;
        }
        if (properties.isAutoConnect()) {
            connect();
        }
    }

    public List<OpsDeviceClient.SimulatorDevice> devices() {
        if (opsDevices == null) {
            throw new IllegalStateException("未配置运维平台设备目录");
        }
        return opsDevices.list();
    }

    /** 从运维平台选择一台设备：校验启用与允许模拟，按档案设置协议与编号，再重建连接。 同一实例同一时刻只运行一台选中设备。 */
    public synchronized Map<String, Object> selectDevice(Long deviceId) {
        if (opsDevices == null) {
            throw new IllegalStateException("未配置运维平台设备目录");
        }
        OpsDeviceClient.SimulatorDevice device = opsDevices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        if (!device.enabled()) {
            throw new IllegalArgumentException("设备已停用: " + device.sn());
        }
        if (!device.allowSimulate()) {
            throw new IllegalArgumentException("设备不允许模拟: " + device.sn());
        }
        Protocol next;
        try {
            next = Protocol.of(device.protocol());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不支持的协议: " + device.protocol());
        }
        if (next == Protocol.YKC16
                && (device.protocolDeviceId() == null
                        || !device.protocolDeviceId().matches("\\d{14}"))) {
            throw new IllegalArgumentException("云快充设备编号必须为14位数字: " + device.protocolDeviceId());
        }
        if (guns.values().stream().anyMatch(g -> g.state == GunState.CHARGING)) {
            throw new IllegalStateException("当前设备有在充订单，请先结束充电后再切换");
        }
        persist();
        disconnect();
        selectedDeviceId = device.id();
        selectedDeviceSn = device.sn();
        protocol = next;
        properties.setProtocol(next.code());
        properties.setPileCode(device.protocolDeviceId());
        properties.setGunCount(Math.max(device.gunCount(), 1));
        properties.setStateFile("./data/simulator-state-" + device.id() + ".properties");
        rateModel = defaultRateModel(
                device.protocolDeviceId().matches("\\d{14}") ? device.protocolDeviceId() : "00000000000000");
        guns.clear();
        for (int i = 1; i <= Math.max(device.gunCount(), 1); i++) {
            guns.put(String.format("%02d", i), new Gun(String.format("%02d", i)));
        }
        history.clear();
        offlineCards.clear();
        pendingTransactions.clear();
        parallelAcks.clear();
        parallelCommands.clear();
        connected = false;
        loggedIn = false;
        rateReady = false;
        lastError = null;
        manualDisconnect = false;
        nextReconnectAt = 0;
        restore();
        connect();
        return state();
    }

    @PreDestroy
    public synchronized void shutdown() {
        guns.values().stream().filter(g -> g.state == GunState.CHARGING).forEach(g -> stopCharge(g, 0x83));
        persist();
        manualDisconnect = true;
        ocppClient.disconnect();
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
    }

    // ------------------------------------------------------------------ 连接

    public synchronized boolean connect() {
        manualDisconnect = false;
        if (protocol == Protocol.OCPP16J) {
            boolean connectedNow = ocppClient.connect();
            if (!connectedNow) {
                nextReconnectAt = System.currentTimeMillis() + properties.getReconnectSeconds() * 1000L;
            }
            return connectedNow;
        }
        if (channel != null && channel.isActive()) {
            return true;
        }
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast("idle", new IdleStateHandler(0, 0, 90, TimeUnit.SECONDS));
                            ch.pipeline().addLast("frameDecoder", new FrameStreamDecoder(key));
                            ch.pipeline().addLast("simulator", new SimulatorHandler(SimulatorRuntime.this));
                        }
                    });
            bootstrap
                    .connect(properties.getPlatformHost(), properties.getPlatformPort())
                    .sync();
            log.info("已连接平台 {}:{}", properties.getPlatformHost(), properties.getPlatformPort());
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("连接平台失败: {}", e.getMessage());
            nextReconnectAt = System.currentTimeMillis() + properties.getReconnectSeconds() * 1000L;
            return false;
        }
    }

    public void disconnect() {
        manualDisconnect = true;
        if (protocol == Protocol.OCPP16J) {
            ocppClient.disconnect();
            ocppClient.clearTransactions();
        }
        if (channel != null) {
            channel.close();
        }
    }

    public synchronized void onConnected(Channel ch) {
        this.channel = ch;
        sequence.set(0);
        connected = true;
        loggedIn = false;
        guns.values().forEach(g -> g.waitingStart = null);
        log.info("TCP 通道已建立: {}", ch.remoteAddress());
        sendLogin();
    }

    public synchronized void onDisconnected() {
        onDisconnected(channel);
    }

    public synchronized void onDisconnected(Channel disconnectedChannel) {
        if (disconnectedChannel != channel) return;
        connected = false;
        loggedIn = false;
        guns.values().forEach(g -> g.waitingStart = null);
        channel = null;
        if (pendingParallelNo != null) cancelParallel();
        log.info("TCP 通道已关闭");
    }

    public void onError(Throwable cause) {
        lastError = cause.getMessage();
        log.warn("通道异常: {}", cause.getMessage());
    }

    // ------------------------------------------------------------------ OCPP 回调

    public synchronized void onOcppConnected(String url) {
        connected = true;
        loggedIn = false;
        channel = null;
        loginSentAt = System.currentTimeMillis();
        lastError = null;
        guns.values().forEach(g -> g.waitingStart = null);
        log.info("OCPP 通道已建立: {}", url);
    }

    public synchronized void onOcppBootAccepted(int interval) {
        connected = true;
        loggedIn = true;
        lastError = null;
        long now = System.currentTimeMillis();
        guns.values().forEach(g -> g.heartbeatAckAt = now);
        log.info("OCPP BootNotification 通过，心跳间隔 {} 秒", interval);
    }

    public synchronized void onOcppDisconnected(String reason) {
        connected = false;
        loggedIn = false;
        channel = null;
        ocppClient.clearTransactions();
        if (pendingParallelNo != null) cancelParallel();
        if (!manualDisconnect && reason != null && !reason.isBlank()) {
            lastError = reason;
        }
        log.info("OCPP 通道已关闭: {}", reason);
    }

    public void onOcppError(String message) {
        lastError = message;
        log.warn("OCPP 异常: {}", message);
    }

    public synchronized void onOcppHeartbeatAck() {
        long now = System.currentTimeMillis();
        guns.values().forEach(g -> g.heartbeatAckAt = now);
    }

    public synchronized void onOcppStartRejected(String gunNo, String reason) {
        Gun gun = guns.get(gunNo);
        if (gun != null && "鉴权中".equals(gun.phase)) {
            gun.phase = gun.plugged ? "已插枪" : "待机";
        }
        lastError = "OCPP 启动失败: " + reason;
        log.warn("OCPP 枪 {} 启动失败: {}", gunNo, reason);
    }

    public synchronized void onOcppTransactionStarted(String gunNo, int transactionId, int startType) {
        Gun gun = guns.get(gunNo);
        if (gun == null) {
            return;
        }
        gun.ocppTransactionId = transactionId;
        gun.phase = gun.plugged ? "已插枪" : "待机";
        String synthetic = properties.getPileCode()
                + gunNo
                + simulatedTime.format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%04d", Math.floorMod(transactionId, 10000));
        beginCharge(gun, synthetic, startType);
        log.info("OCPP 枪 {} 开始充电，整数交易号 {}", gunNo, transactionId);
    }

    public synchronized boolean ocppCanStart(String gunNo) {
        Gun gun = guns.get(gunNo);
        return gun != null && loggedIn && gun.plugged && gun.allowWork && !gun.fault && gun.state != GunState.CHARGING;
    }

    public boolean ocppCanStop(String gunNo) {
        Gun gun = guns.get(gunNo);
        return gun != null && gun.state == GunState.CHARGING;
    }

    public synchronized void ocppMarkAuthorizing(String gunNo) {
        Gun gun = guns.get(gunNo);
        if (gun != null) {
            gun.phase = "鉴权中";
            gun.authDeadline = System.currentTimeMillis() + 15000;
        }
    }

    public synchronized BigDecimal ocppMeterWh(String gunNo) {
        Gun gun = guns.get(gunNo);
        return gun == null ? BigDecimal.ZERO : gun.meter.multiply(new BigDecimal("1000"));
    }

    public synchronized String gunRuntimeStatus(String gunNo) {
        Gun gun = guns.get(gunNo);
        if (gun == null) {
            return "Available";
        }
        if (gun.fault) {
            return "Faulted";
        }
        if (gun.state == GunState.CHARGING) {
            return "Charging";
        }
        if (!gun.allowWork) {
            return "Unavailable";
        }
        return gun.plugged ? "Preparing" : "Available";
    }

    public synchronized void ocppRemoteStop(String gunNo) {
        Gun gun = guns.get(gunNo);
        if (gun == null || gun.state != GunState.CHARGING) {
            return;
        }
        advanceClock();
        advanceCharge(gun);
        stopCharge(gun, 0x40);
        log.info("OCPP 远程停机完成，枪 {}", gunNo);
    }

    public synchronized void ocppReset(boolean hard) {
        advanceClock();
        guns.values().stream().filter(g -> g.state == GunState.CHARGING).forEach(g -> stopCharge(g, 0x83));
        if (hard) {
            ocppClient.disconnect();
            nextReconnectAt = System.currentTimeMillis() + 1000;
        } else {
            rebootWhenIdle = true;
        }
        log.info("OCPP 收到 {} Reset，模拟重启", hard ? "Hard" : "Soft");
    }

    public synchronized String ocppUnlock(int connectorId) {
        Gun gun = guns.get(String.format("%02d", connectorId));
        if (gun == null || gun.state == GunState.CHARGING) {
            return "NotSupported";
        }
        gun.lockStatus = 255;
        return "Unlocked";
    }

    public synchronized void ocppSetAvailability(int connectorId, boolean operative) {
        if (connectorId <= 0) {
            guns.values().forEach(g -> g.allowWork = operative);
        } else {
            Gun gun = guns.get(String.format("%02d", connectorId));
            if (gun != null) {
                gun.allowWork = operative;
            }
        }
        persist();
        guns.forEach((no, gun) -> ocppClient.statusNotification(Integer.parseInt(no), gunRuntimeStatus(no), "NoError"));
    }

    public synchronized void ocppTrigger(String requestedMessage, Integer connectorId) {
        if (requestedMessage == null) {
            return;
        }
        if ("Heartbeat".equalsIgnoreCase(requestedMessage)) {
            ocppClient.heartbeat();
        } else if ("StatusNotification".equalsIgnoreCase(requestedMessage)) {
            if (connectorId != null && connectorId > 0) {
                ocppClient.statusNotification(
                        connectorId, gunRuntimeStatus(String.format("%02d", connectorId)), "NoError");
            } else {
                guns.forEach((no, gun) ->
                        ocppClient.statusNotification(Integer.parseInt(no), gunRuntimeStatus(no), "NoError"));
            }
        } else if ("MeterValues".equalsIgnoreCase(requestedMessage)) {
            guns.values().stream().filter(g -> g.state == GunState.CHARGING).forEach(this::sendRealtime);
        }
    }

    public synchronized void ocppFirmwareUpdate(String location) {
        upgradeStatus = "OCPP 固件升级已受理: " + location;
    }

    // ------------------------------------------------------------------ 上行

    private void sendLogin() {
        if (protocol == Protocol.OCPP16J) {
            return;
        }
        RegistrationFrames.Login login = new RegistrationFrames.Login(
                properties.getPileCode(),
                properties.getPileType(),
                Math.max(properties.getGunCount(), 1),
                properties.getProtocolVersion(),
                properties.getProgramVersion(),
                properties.getNetworkType(),
                properties.getSimCard(),
                properties.getOperator());
        loginSentAt = System.currentTimeMillis();
        send(FrameType.LOGIN, RegistrationFrames.encodeLogin(login));
    }

    private void sendHeartbeat(Gun gun) {
        if (!loggedIn) {
            return;
        }
        if (protocol == Protocol.OCPP16J) {
            ocppClient.heartbeat();
            gun.lastHeartbeatAt = System.currentTimeMillis();
            return;
        }
        send(
                FrameType.HEARTBEAT,
                RegistrationFrames.encodeHeartbeat(
                        new RegistrationFrames.Heartbeat(properties.getPileCode(), gun.gunNo, gun.fault)));
        gun.lastHeartbeatAt = System.currentTimeMillis();
    }

    private void sendRealtime(Gun gun) {
        if (!loggedIn) {
            return;
        }
        if (protocol == Protocol.OCPP16J) {
            if (gun.state == GunState.CHARGING) {
                ocppClient.meterValues(gun.gunNo, gun.energy, gun.voltage, gun.current, gun.soc);
            }
            gun.lastRealtimeAt = System.currentTimeMillis();
            return;
        }
        FrameType type = FrameType.REALTIME;
        byte[] body = RealtimeFrames.encodeRealtime(buildRealtime(gun));
        send(type, body);
        gun.lastRealtimeAt = System.currentTimeMillis();
    }

    private RealtimeFrames.Realtime buildRealtime(Gun gun) {
        boolean charging = gun.state == GunState.CHARGING;
        return new RealtimeFrames.Realtime(
                gun.transactionNo == null ? "0".repeat(32) : gun.transactionNo,
                properties.getPileCode(),
                gun.gunNo,
                gun.state.code(),
                gun.plugged ? 0 : 1,
                gun.plugged ? 1 : 0,
                charging ? gun.voltage : BigDecimal.ZERO,
                charging ? gun.current : BigDecimal.ZERO,
                charging ? 35 : 20,
                "0000000000000000",
                charging ? gun.soc : 0,
                charging ? 30 : 20,
                gun.totalMinutes,
                gun.remainMinutes,
                gun.energy,
                gun.ledger == null ? gun.energy : gun.ledger.lossEnergy(),
                gun.amount,
                gun.fault ? 0x0001 : 0,
                null);
    }

    private void sendStartRequest(Gun gun) {
        OperationFrames.StartRequest request = new OperationFrames.StartRequest(
                properties.getPileCode(), gun.gunNo, 0x01, false, gun.physicalCard, "0".repeat(32), "");
        send(FrameType.START_REQUEST, OperationFrames.encodeStartRequest(request));
    }

    private void sendTransaction(OperationFrames.Transaction tx) {
        if (protocol == Protocol.OCPP16J) {
            return;
        }
        pendingTransactions.put(
                tx.transactionNo(),
                new PendingTransaction(
                        tx,
                        properties.getTransactionMaxRetries(),
                        System.currentTimeMillis() + properties.getTransactionRetrySeconds() * 1000L));
        persist();
        send(FrameType.TRANSACTION, OperationFrames.encodeTransaction(tx));
        log.info(
                "桩 {} 上送交易记录 {}，电量 {} kWh，金额 {} 元",
                properties.getPileCode(),
                tx.transactionNo(),
                tx.totalEnergy(),
                tx.totalAmount());
    }

    private boolean send(FrameType type, byte[] body) {
        return send(type, body, null);
    }

    private boolean send(FrameType type, byte[] body, Integer forcedSequence) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return false;
        }
        int seq = forcedSequence == null ? nextSequence() : forcedSequence;
        Frame frame = new Frame(seq, properties.isEncryptionEnabled(), type.code(), body);
        try (var operation = telemetry.start("simulator", "send", frame, telemetry.capture())) {
            try {
                byte[] wire = FrameCodec.encode(frame, properties.isEncryptionEnabled(), key);
                recordLog("OUT", frame, wire);
                var write = ch.writeAndFlush(Unpooled.wrappedBuffer(wire));
                operation.completeOn(write);
                write.addListener(future -> {
                    if (!future.isSuccess()) {
                        log.warn(
                                "发送 {} 失败: {}",
                                type.label(),
                                future.cause() == null
                                        ? "unknown"
                                        : future.cause().getMessage());
                    }
                });
                return true;
            } catch (ProtocolException e) {
                operation.fail(e);
                lastError = e.getMessage();
                log.warn("编码 {} 失败: {}", type.label(), e.getMessage());
                return false;
            } catch (RuntimeException e) {
                operation.fail(e);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ 下行

    public synchronized void onFrame(Frame frame) {
        try (var operation = telemetry.start("simulator", "receive", frame, null)) {
            try {
                handleFrame(frame);
            } catch (RuntimeException e) {
                operation.fail(e);
                throw e;
            }
        }
    }

    private void handleFrame(Frame frame) {
        recordLog("IN", frame, null);
        FrameType type = FrameType.ofOrNull(frame.frameType());
        if (type == null) {
            telemetry.failure(new IllegalArgumentException("unknown frame type"));
            log.warn("收到未知帧类型 0x{}", String.format("%02X", frame.frameType()));
            return;
        }
        try {
            if (type.direction() != FrameType.Direction.DOWN) return;
            if (type != FrameType.REMOTE_START
                    && type != FrameType.UPDATE
                    && type != FrameType.REBOOT
                    && type != FrameType.BALANCE_UPDATE
                    && type != FrameType.PARAM_SET) {
                Object decoded = top.yealike.ipe.protocol.codec.FramePayloads.decode(type, frame.body());
                try {
                    Object pile = decoded.getClass().getMethod("pileCode").invoke(decoded);
                    if (!properties.getPileCode().equals(pile)) throw new IllegalArgumentException("下行桩号不匹配");
                } catch (NoSuchMethodException ignored) {
                } catch (ReflectiveOperationException e) {
                    throw new IllegalArgumentException("解析下行桩号失败", e);
                }
            }
            switch (type) {
                case LOGIN_ACK -> handleLoginAck(frame);
                case HEARTBEAT_ACK -> {
                    var ack = RegistrationFrames.decodeHeartbeatAck(frame.body());
                    Gun g = guns.get(ack.gunNo());
                    if (g != null) g.heartbeatAckAt = System.currentTimeMillis();
                }
                case RATE_VERIFY_ACK -> handleRateVerifyAck(frame);
                case RATE_RESPONSE, RATE_SET -> handleRateModel(frame, type);
                case READ_REALTIME -> {
                    RealtimeFrames.ReadRealtime req = RealtimeFrames.decodeReadRealtime(frame.body());
                    Gun gun = guns.get(req.gunNo());
                    if (gun != null) {
                        send(FrameType.REALTIME, RealtimeFrames.encodeRealtime(buildRealtime(gun)), frame.sequence());
                    }
                }
                case START_ACK -> handleStartAck(frame);
                case REMOTE_START -> handleRemoteStart(frame);
                case PARALLEL_REMOTE_START -> handleParallelRemote(frame);
                case PARALLEL_START_ACK -> handleParallelAck(frame);
                case REMOTE_STOP -> handleRemoteStop(frame);
                case TRANSACTION_ACK -> {
                    OperationFrames.TransactionAck ack = OperationFrames.decodeTransactionAck(frame.body());
                    if (ack.success()) {
                        pendingTransactions.remove(ack.transactionNo());
                        persist();
                    }
                    log.info("交易记录 {} 平台确认={}", ack.transactionNo(), ack.success());
                }
                case PARAM_SET -> handleParamSet(frame);
                case TIME_SYNC -> handleTimeSync(frame);
                case RATE_SET_ACK -> log.debug("费率设置应答");
                case REBOOT -> handleReboot(frame);
                case UPDATE -> handleUpdate(frame);
                case BALANCE_UPDATE -> handleBalance(frame);
                case CARD_SYNC -> handleCardSync(frame);
                case CARD_CLEAR -> handleCardClear(frame);
                case CARD_QUERY -> handleCardQuery(frame);
                case LOCK_CONTROL -> handleLock(frame);
                case PARAM_SET_ACK,
                        REBOOT_ACK,
                        UPDATE_ACK,
                        BALANCE_ACK,
                        CARD_SYNC_ACK,
                        CARD_CLEAR_ACK,
                        CARD_QUERY_ACK,
                        LOCK_CONTROL_ACK,
                        TIME_SYNC_ACK -> log.debug("平台应答 {}", type.label());
                default -> log.debug("未处理下行帧 {}", type.label());
            }
        } catch (RuntimeException e) {
            telemetry.failure(e);
            lastError = e.getMessage();
            log.warn("处理下行帧 {} 失败", type.label(), e);
            if (type == FrameType.RATE_SET)
                send(
                        FrameType.RATE_SET_ACK,
                        RegistrationFrames.encodeRateSetAck(properties.getPileCode(), false),
                        frame.sequence());
        }
    }

    private void handleLoginAck(Frame frame) {
        RegistrationFrames.LoginAck ack = RegistrationFrames.decodeLoginAck(frame.body());
        if (!ack.success()) {
            log.warn("平台拒绝登录: {}", ack.pileCode());
            lastError = "平台拒绝登录";
            return;
        }
        loggedIn = true;
        rateReady = false;
        rateRequestedAt = System.currentTimeMillis();
        guns.values().forEach(g -> g.heartbeatAckAt = System.currentTimeMillis());
        pendingTransactions.values().forEach(p -> {
            p.nextAt = 0;
            p.retriesLeft = properties.getTransactionMaxRetries();
        });
        lastError = null;
        log.info("登录成功，桩号 {}", properties.getPileCode());
        send(
                FrameType.RATE_VERIFY,
                RegistrationFrames.encodeRateVerify(
                        new RegistrationFrames.RateVerify(properties.getPileCode(), rateModel.modelNo())));
    }

    private void handleRateVerifyAck(Frame frame) {
        RegistrationFrames.RateVerifyAck ack = RegistrationFrames.decodeRateVerifyAck(frame.body());
        rateReady = ack.consistent();
        rateRequestedAt = System.currentTimeMillis();
        if (!ack.consistent()) {
            log.info("平台计费模型 {} 与本地不一致，请求新模型", ack.modelNo());
            send(
                    FrameType.RATE_REQUEST,
                    RegistrationFrames.encodeRateRequest(new RegistrationFrames.RateRequest(properties.getPileCode())));
        } else {
            log.info("计费模型校验一致: {}", ack.modelNo());
        }
    }

    private void handleRateModel(Frame frame, FrameType type) {
        RegistrationFrames.RateModel model = RegistrationFrames.decodeRateModel(frame.body());
        ChargingLedger.validate(model);
        if (!model.pileCode().equals(properties.getPileCode())) throw new IllegalArgumentException("费率桩号不匹配");
        rateModel = model;
        rateReady = true;
        persist();
        log.info(
                "收到计费模型 {}，尖={} 峰={} 平={} 谷={}",
                model.modelNo(),
                model.sharpEnergy(),
                model.peakEnergy(),
                model.flatEnergy(),
                model.valleyEnergy());
        if (type == FrameType.RATE_SET) {
            send(
                    FrameType.RATE_SET_ACK,
                    RegistrationFrames.encodeRateSetAck(properties.getPileCode(), true),
                    frame.sequence());
        }
    }

    private void handleStartAck(Frame frame) {
        OperationFrames.StartAck ack = OperationFrames.decodeStartAck(frame.body());
        Gun gun = guns.get(ack.gunNo());
        if (gun == null) {
            return;
        }
        if (!ack.success()) {
            gun.phase = "已插枪";
            log.warn("平台拒绝启动: 原因={}", ack.failReason());
            return;
        }
        if (!gun.phase.equals("鉴权中")) return;
        gun.phase = "已插枪";
        if (startFailure(gun) != 0) return;
        gun.balance = ack.balance();
        beginCharge(gun, ack.transactionNo(), gun.startType);
    }

    private void handleRemoteStart(Frame frame) {
        OperationFrames.RemoteStart command = OperationFrames.decodeRemoteStart(frame.body());
        Gun gun = guns.get(command.gunNo());
        int failReason = !command.pileCode().equals(properties.getPileCode()) ? 1 : startFailure(gun);
        boolean success = failReason == 0;
        if (failReason == 5) {
            gun.waitingStart = command;
            gun.waitingUntil = System.currentTimeMillis() + 60000;
            gun.waitingSequence = frame.sequence();
        }
        send(
                FrameType.REMOTE_START_ACK,
                OperationFrames.encodeRemoteStartAck(new OperationFrames.RemoteStartAck(
                        command.transactionNo(), properties.getPileCode(), command.gunNo(), success, failReason)),
                frame.sequence());
        if (success) {
            gun.parallelNo = null;
            gun.mainGun = 0;
            gun.balance = command.balance();
            gun.physicalCard = command.physicalCard();
            beginCharge(gun, command.transactionNo(), 0x01);
            log.info("远程启动成功，流水号 {}", command.transactionNo());
        } else {
            log.info("远程启动失败，原因={}", failReason);
        }
    }

    private void handleRemoteStop(Frame frame) {
        OperationFrames.RemoteStop command = OperationFrames.decodeRemoteStop(frame.body());
        Gun gun = guns.get(command.gunNo());
        boolean success = gun != null && gun.state == GunState.CHARGING;
        int failReason = success ? 0x00 : 0x02;
        send(
                FrameType.STOP_ACK,
                OperationFrames.encodeStopAck(
                        new OperationFrames.StopAck(properties.getPileCode(), command.gunNo(), success, failReason)),
                frame.sequence());
        if (success) {
            stopCharge(gun, 0x40);
            log.info("远程停机成功，枪 {}", command.gunNo());
        }
    }

    private void handleParamSet(Frame frame) {
        SettingFrames.ParamSet set = SettingFrames.decodeParamSet(frame.body());
        boolean valid = set.pileCode().equals(properties.getPileCode())
                && set.maxPowerPercent() >= 30
                && set.maxPowerPercent() <= 100;
        if (valid) {
            advanceClock();
            guns.values().forEach(this::advanceCharge);
            maxPowerPercent = set.maxPowerPercent();
            guns.values().forEach(gun -> {
                gun.allowWork = set.allowWork();
                if (!set.allowWork() && gun.state == GunState.CHARGING) stopCharge(gun, 0x6A);
            });
            persist();
        }
        send(
                FrameType.PARAM_SET_ACK,
                SettingFrames.encodeParamSetAck(properties.getPileCode(), valid),
                frame.sequence());
    }

    private void handleTimeSync(Frame frame) {
        SettingFrames.TimeSync sync = SettingFrames.decodeTimeSync(frame.body());
        advanceClock();
        guns.values().forEach(this::advanceCharge);
        Duration adjustment = Duration.between(simulatedTime, sync.time());
        for (Gun g : guns.values())
            if (g.state == GunState.CHARGING) {
                g.startTime = g.startTime.plus(adjustment);
                g.lastAdvance = sync.time();
            }
        simulatedTime = sync.time();
        clockNanos = System.nanoTime();
        send(
                FrameType.TIME_SYNC_ACK,
                SettingFrames.encodeTimeSyncAck(new SettingFrames.TimeSync(properties.getPileCode(), simulatedTime)),
                frame.sequence());
        log.info("对时: {}", sync.time());
    }

    private void handleReboot(Frame frame) {
        MaintenanceFrames.Reboot reboot = MaintenanceFrames.decodeReboot(frame.body());
        boolean valid =
                reboot.pileCode().equals(properties.getPileCode()) && (reboot.control() == 1 || reboot.control() == 2);
        send(
                FrameType.REBOOT_ACK,
                MaintenanceFrames.encodeRebootAck(properties.getPileCode(), valid),
                frame.sequence());
        if (valid) {
            rebootWhenIdle = true;
            if (reboot.control() == 1)
                guns.values().stream().filter(g -> g.state == GunState.CHARGING).forEach(g -> stopCharge(g, 0x83));
        }
    }

    private void handleUpdate(Frame frame) {
        MaintenanceFrames.Update update = MaintenanceFrames.decodeUpdate(frame.body());
        if (update.control() == 2 && guns.values().stream().anyMatch(g -> g.state == GunState.CHARGING)) {
            pendingUpdate = update;
            updateSequence = frame.sequence();
            upgradeStatus = "等待空闲升级";
            return;
        }
        completeUpdate(update, frame.sequence());
    }

    private void completeUpdate(MaintenanceFrames.Update update, int seq) {
        int status = !update.pileCode().equals(properties.getPileCode())
                ? 1
                : update.pileModel() != (properties.getPileType() == 1 ? 2 : 1) ? 2 : upgradeResult;
        upgradeStatus = status == 0 ? "模拟下载、校验和升级完成: " + update.filePath() : "升级失败: " + status;
        send(FrameType.UPDATE_ACK, MaintenanceFrames.encodeUpdateAck(properties.getPileCode(), status), seq);
    }

    public synchronized void upgradeScenario(int result) {
        if (result < 0 || result > 3) throw new IllegalArgumentException("升级结果0~3");
        upgradeResult = result;
    }

    private void handleBalance(Frame frame) {
        OperationFrames.BalanceUpdate update = OperationFrames.decodeBalanceUpdate(frame.body());
        Gun gun = guns.get(update.gunNo());
        int result = !update.pileCode().equals(properties.getPileCode())
                ? 1
                : gun == null
                                || (!update.physicalCard().equals("0000000000000000")
                                        && !gun.physicalCard.equalsIgnoreCase(update.physicalCard()))
                        ? 2
                        : 0;
        if (result == 0) gun.balance = update.balance();
        send(
                FrameType.BALANCE_ACK,
                OperationFrames.encodeBalanceAck(
                        new OperationFrames.BalanceAck(properties.getPileCode(), update.physicalCard(), result)),
                frame.sequence());
    }

    private void handleCardSync(Frame frame) {
        OperationFrames.CardList list = OperationFrames.decodeCardSync(frame.body());
        list.cards().forEach(c -> offlineCards.put(c.physicalCard().toUpperCase(), c));
        persist();
        send(
                FrameType.CARD_SYNC_ACK,
                OperationFrames.encodeCardSyncAck(properties.getPileCode(), true, 0x00),
                frame.sequence());
        log.info("离线卡同步 {} 张", list.cards().size());
    }

    private void handleCardClear(Frame frame) {
        OperationFrames.CardClear clear = OperationFrames.decodeCardClear(frame.body());
        List<OperationFrames.CardClearAck.CardClearResult> results = new ArrayList<>();
        for (String card : clear.physicalCards()) {
            offlineCards.remove(card.toUpperCase());
            results.add(new OperationFrames.CardClearAck.CardClearResult(card, true, 0));
        }
        persist();
        send(
                FrameType.CARD_CLEAR_ACK,
                OperationFrames.encodeCardClearAck(new OperationFrames.CardClearAck(properties.getPileCode(), results)),
                frame.sequence());
    }

    private void handleCardQuery(Frame frame) {
        OperationFrames.CardQuery query = OperationFrames.decodeCardQuery(frame.body());
        List<OperationFrames.CardQueryAck.CardQueryResult> results = new ArrayList<>();
        for (String card : query.physicalCards()) {
            results.add(new OperationFrames.CardQueryAck.CardQueryResult(
                    card, offlineCards.containsKey(card.toUpperCase())));
        }
        send(
                FrameType.CARD_QUERY_ACK,
                OperationFrames.encodeCardQueryAck(new OperationFrames.CardQueryAck(properties.getPileCode(), results)),
                frame.sequence());
    }

    private void handleLock(Frame frame) {
        MaintenanceFrames.LockControl control = MaintenanceFrames.decodeLockControl(frame.body());
        Gun gun = guns.get(String.format("%02d", control.gunNo()));
        boolean valid = gun != null
                && (control.action() == 85 || control.action() == 255)
                && !(gun.plugged && control.action() == 85);
        if (valid) gun.lockStatus = control.action();
        send(
                FrameType.LOCK_CONTROL_ACK,
                MaintenanceFrames.encodeLockControlAck(
                        new MaintenanceFrames.LockControlAck(properties.getPileCode(), control.gunNo(), valid)),
                frame.sequence());
        if (valid) reportLock(gun);
    }

    // ------------------------------------------------------------------ 状态机

    private int startFailure(Gun gun) {
        if (gun == null) return 1;
        if (!gun.allowWork || gun.fault || (!isOcpp() && loggedIn && !rateReady)) return 3;
        if (gun.state == GunState.CHARGING || gun.phase.equals("鉴权中")) return 2;
        if (!gun.plugged) return 5;
        return 0;
    }

    private void beginCharge(Gun gun, String transactionNo, int startType) {
        if (gun.state == GunState.CHARGING) return;
        advanceClock();
        gun.state = GunState.CHARGING;
        gun.phase = "握手";
        gun.transactionNo = transactionNo;
        gun.startTime = simulatedTime;
        gun.lastAdvance = simulatedTime;
        gun.ledger = new ChargingLedger(rateModel);
        gun.meterStart = gun.meter;
        gun.energy = BigDecimal.ZERO;
        gun.amount = BigDecimal.ZERO;
        gun.socValue = gun.initialSoc;
        gun.soc = gun.initialSoc;
        gun.startType = startType;
        gun.totalMinutes = 0;
        gun.remainMinutes = 0;
        gun.lastRealtimeAt = 0;
        gun.lastBmsAt = 0;
        if (!isOcpp()) {
            sendBms(gun, FrameType.CHARGE_HANDSHAKE);
        }
        sendRealtime(gun);
        if (isOcpp()) {
            ocppClient.statusNotification(Integer.parseInt(gun.gunNo), "Charging", "NoError");
        }
        persist();
    }

    private synchronized boolean stopCharge(Gun gun, int reason) {
        if (gun.state != GunState.CHARGING) return false;
        gun.phase = "结算中";
        if (!isOcpp()) {
            sendBms(gun, reason == 0x41 ? FrameType.BMS_ABORT : FrameType.CHARGER_ABORT);
            sendBms(gun, FrameType.CHARGE_END);
        }
        OperationFrames.Transaction tx = buildTransaction(gun, reason);
        gun.state = gun.fault ? GunState.FAULT : GunState.IDLE;
        gun.phase = "已结束";
        gun.voltage = BigDecimal.ZERO;
        gun.current = BigDecimal.ZERO;
        gun.remainMinutes = 0;
        gun.meter = gun.meterStart.add(gun.energy);
        history.add(0, tx);
        if (history.size() > 100) history.removeLast();
        if (isOcpp()) {
            ocppClient.stopTransaction(gun.gunNo, gun.energy, reason);
            gun.ocppTransactionId = null;
            sendRealtime(gun);
            ocppClient.statusNotification(
                    Integer.parseInt(gun.gunNo), gun.allowWork ? "Available" : "Unavailable", "NoError");
        } else {
            sendTransaction(tx);
            sendRealtime(gun);
        }
        persist();
        return true;
    }

    private OperationFrames.Transaction buildTransaction(Gun gun, int reason) {
        return new OperationFrames.Transaction(
                gun.transactionNo,
                properties.getPileCode(),
                gun.gunNo,
                gun.startTime,
                simulatedTime,
                gun.ledger.segment(0),
                gun.ledger.segment(1),
                gun.ledger.segment(2),
                gun.ledger.segment(3),
                gun.meterStart,
                gun.meterStart.add(gun.energy),
                gun.energy,
                gun.ledger.lossEnergy(),
                gun.amount,
                gun.vin,
                gun.startType,
                simulatedTime,
                reason,
                gun.physicalCard);
    }

    private BigDecimal unitPrice() {
        int p = ChargingLedger.period(rateModel, simulatedTime);
        return ChargingLedger.energyPrice(rateModel, p).add(ChargingLedger.servicePrice(rateModel, p));
    }

    private void advanceClock() {
        long now = System.nanoTime();
        simulatedTime = simulatedTime.plusNanos((long) ((now - clockNanos) * speed));
        clockNanos = now;
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void tick() {
        advanceClock();
        long now = System.currentTimeMillis();
        for (Gun gun : guns.values()) {
            advanceCharge(gun);
            if (gun.phase.equals("鉴权中") && pendingParallelNo == null && now > gun.authDeadline) gun.phase = "鉴权超时";
        }
        if (protocol == Protocol.OCPP16J) {
            tickOcpp(now);
            return;
        }
        if (!connected && !manualDisconnect && now >= nextReconnectAt) {
            nextReconnectAt = now + properties.getReconnectSeconds() * 1000L;
            connect();
        }
        if (rebootWhenIdle && guns.values().stream().noneMatch(g -> g.state == GunState.CHARGING)) {
            rebootWhenIdle = false;
            if (channel != null) channel.close();
            nextReconnectAt = now + 1000;
            return;
        }
        if (pendingUpdate != null && guns.values().stream().noneMatch(g -> g.state == GunState.CHARGING)) {
            completeUpdate(pendingUpdate, updateSequence);
            pendingUpdate = null;
        }
        if (pendingParallelNo != null && now > parallelDeadline) cancelParallel();
        if (guns.values().stream().anyMatch(g -> g.state == GunState.CHARGING)) persist();
        if (connected && !loggedIn && now - loginSentAt > 30000) {
            if (channel != null) channel.close();
            return;
        }
        if (!loggedIn) return;
        if (!rateReady && now - rateRequestedAt >= 10000) {
            rateRequestedAt = now;
            send(
                    FrameType.RATE_REQUEST,
                    RegistrationFrames.encodeRateRequest(new RegistrationFrames.RateRequest(properties.getPileCode())));
        }
        if (guns.values().stream().anyMatch(g -> now - g.heartbeatAckAt > properties.getHeartbeatSeconds() * 3000L)) {
            if (channel != null) channel.close();
            return;
        }
        for (Gun gun : guns.values()) {
            if (gun.waitingStart != null && now > gun.waitingUntil) gun.waitingStart = null;
            if (now - gun.lastLockAt >= 300000) reportLock(gun);
            if (now - gun.lastHeartbeatAt >= properties.getHeartbeatSeconds() * 1000L) sendHeartbeat(gun);
            long interval = (gun.state == GunState.CHARGING
                            ? properties.getChargingRealtimeSeconds()
                            : properties.getIdleRealtimeSeconds())
                    * 1000L;
            if (now - gun.lastRealtimeAt >= interval) sendRealtime(gun);
            if (gun.state == GunState.CHARGING && now - gun.lastBmsAt >= 15000) {
                sendBms(gun, FrameType.BMS_DEMAND);
                sendBms(gun, FrameType.BMS_INFO);
                gun.lastBmsAt = now;
            }
        }
        retryTransactions(now);
    }

    private void tickOcpp(long now) {
        if (rebootWhenIdle && guns.values().stream().noneMatch(g -> g.state == GunState.CHARGING)) {
            rebootWhenIdle = false;
            ocppClient.disconnect();
            nextReconnectAt = now + 1000;
            return;
        }
        if (!connected && !manualDisconnect && now >= nextReconnectAt) {
            nextReconnectAt = now + properties.getReconnectSeconds() * 1000L;
            connect();
        }
        if (guns.values().stream().anyMatch(g -> g.state == GunState.CHARGING)) {
            persist();
        }
        if (connected && !loggedIn && now - loginSentAt > 30000) {
            lastError = "OCPP BootNotification 超时";
            ocppClient.disconnect();
            return;
        }
        if (!loggedIn) {
            return;
        }
        if (guns.values().stream().anyMatch(g -> now - g.heartbeatAckAt > properties.getHeartbeatSeconds() * 3000L)) {
            lastError = "OCPP 心跳应答超时";
            ocppClient.disconnect();
            return;
        }
        for (Gun gun : guns.values()) {
            if (gun.waitingStart != null && now > gun.waitingUntil) gun.waitingStart = null;
            if (now - gun.lastHeartbeatAt >= properties.getHeartbeatSeconds() * 1000L) {
                ocppClient.heartbeat();
                gun.lastHeartbeatAt = now;
            }
            long interval = (gun.state == GunState.CHARGING
                            ? properties.getChargingRealtimeSeconds()
                            : properties.getIdleRealtimeSeconds())
                    * 1000L;
            if (now - gun.lastRealtimeAt >= interval) {
                sendRealtime(gun);
            }
        }
    }

    private boolean isOcpp() {
        return protocol == Protocol.OCPP16J;
    }

    private void advanceCharge(Gun gun) {
        if (gun.state != GunState.CHARGING) return;
        LocalDateTime from = gun.lastAdvance, to = simulatedTime;
        if (!to.isAfter(from)) return;
        LocalDateTime config = gun.startTime.plusSeconds(2), ready = gun.startTime.plusSeconds(4);
        if (gun.phase.equals("握手") && !to.isBefore(config)) {
            gun.phase = "参数配置";
            sendBms(gun, FrameType.PARAM_CONFIG);
        }
        if (!to.isBefore(ready)) {
            gun.phase = gun.socValue >= 80 ? "恒压充电" : "恒流充电";
            if (from.isBefore(ready)) from = ready;
            // Integrate in <=1 simulated second steps so acceleration preserves the taper/SOC
            // curve.
            while (from.isBefore(to) && gun.state == GunState.CHARGING) {
                LocalDateTime end = from.plusSeconds(1).isBefore(to) ? from.plusSeconds(1) : to;
                double taper = gun.socValue < 80 ? 1 : Math.max(0.1, (100 - gun.socValue) / 20);
                gun.voltage = BigDecimal.valueOf(properties.getRatedVoltage());
                gun.current = BigDecimal.valueOf(properties.getRatedCurrent() * maxPowerPercent / 100.0 * taper);
                BigDecimal power = gun.voltage.multiply(gun.current).movePointLeft(3);
                double needed = gun.capacityKwh * (100 - gun.socValue) / 100;
                double seconds = Duration.between(from, end).toNanos() / 1e9;
                if (power.doubleValue() * seconds / 3600 > needed)
                    end = from.plusNanos(Math.max(1, (long) (needed / power.doubleValue() * 3600e9)));
                gun.ledger.integrate(from, end, power);
                gun.energy = gun.ledger.energy();
                gun.amount = gun.ledger.amount();
                gun.socValue = Math.min(100, gun.initialSoc + gun.energy.doubleValue() / gun.capacityKwh * 100);
                gun.soc = (int) gun.socValue;
                gun.totalMinutes = (int)
                        Math.min(65535, Duration.between(gun.startTime, end).toMinutes());
                gun.remainMinutes = (int) Math.min(
                        600,
                        Math.ceil(Math.max(0, gun.capacityKwh * (100 - gun.socValue) / 100)
                                / Math.max(.001, power.doubleValue())
                                * 60));
                from = end;
                if (gun.socValue >= 99.9999) {
                    gun.soc = 100;
                    simulatedStop(gun, end, 0x41);
                } else if (gun.targetEnergy > 0 && gun.energy.doubleValue() >= gun.targetEnergy)
                    simulatedStop(gun, end, 0x42);
                else if (gun.targetAmount > 0 && gun.amount.doubleValue() >= gun.targetAmount)
                    simulatedStop(gun, end, 0x43);
                else if (gun.targetMinutes > 0
                        && Duration.between(gun.startTime, end).getSeconds() >= gun.targetMinutes * 60L)
                    simulatedStop(gun, end, 0x44);
                else if (gun.amount.compareTo(gun.balance) >= 0 && gun.startType != 4) simulatedStop(gun, end, 0x6E);
            }
        }
        gun.lastAdvance = to;
    }

    private void simulatedStop(Gun gun, LocalDateTime end, int reason) {
        LocalDateTime save = simulatedTime;
        simulatedTime = end;
        stopCharge(gun, reason);
        simulatedTime = save;
    }

    private void retryTransactions(long now) {
        for (Map.Entry<String, PendingTransaction> entry : pendingTransactions.entrySet()) {
            PendingTransaction pending = entry.getValue();
            if (now < pending.nextAt) {
                continue;
            }
            if (pending.retriesLeft <= 0) {
                pending.nextAt = Long.MAX_VALUE;
                persist();
                log.warn("交易记录 {} 重试次数用尽", entry.getKey());
                continue;
            }
            pending.retriesLeft--;
            pending.nextAt = now + properties.getTransactionRetrySeconds() * 1000L;
            send(FrameType.TRANSACTION, OperationFrames.encodeTransaction(pending.transaction));
            log.info("重发交易记录 {}，剩余重试 {} 次", entry.getKey(), pending.retriesLeft);
        }
    }

    // ------------------------------------------------------------------ 控制接口

    public synchronized String plug(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (gun.state == GunState.CHARGING) {
            return "充电中不能拔插枪";
        }
        gun.plugged = true;
        gun.phase = "已插枪";
        reportLock(gun);
        if (isOcpp()) {
            ocppClient.statusNotification(Integer.parseInt(gun.gunNo), "Preparing", "NoError");
        }
        if (gun.waitingStart != null && loggedIn && System.currentTimeMillis() <= gun.waitingUntil) {
            OperationFrames.RemoteStart cmd = gun.waitingStart;
            gun.waitingStart = null;
            handleRemoteStart(new Frame(
                    gun.waitingSequence,
                    properties.isEncryptionEnabled(),
                    FrameType.REMOTE_START.code(),
                    OperationFrames.encodeRemoteStart(cmd)));
        }
        sendRealtime(gun);
        return "已插枪";
    }

    public synchronized String unplug(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (gun.state == GunState.CHARGING) {
            return "充电中不能拔枪，请先停止充电";
        }
        gun.plugged = false;
        gun.phase = "待机";
        reportLock(gun);
        if (isOcpp()) {
            ocppClient.statusNotification(
                    Integer.parseInt(gun.gunNo), gun.allowWork ? "Available" : "Unavailable", "NoError");
        }
        sendRealtime(gun);
        return "已拔枪";
    }

    public synchronized String fault(String gunNo, boolean enable) {
        Gun gun = requireGun(gunNo);
        if (!enable && !gun.fault) return "当前无故障";
        advanceClock();
        advanceCharge(gun);
        gun.fault = enable;
        if (enable && gun.state == GunState.CHARGING) {
            stopCharge(gun, 0x72);
        }
        gun.state = enable ? GunState.FAULT : GunState.IDLE;
        gun.phase = enable ? "故障" : "待机";
        if (isOcpp()) {
            ocppClient.statusNotification(
                    Integer.parseInt(gun.gunNo),
                    enable ? "Faulted" : (gun.allowWork ? "Available" : "Unavailable"),
                    "NoError");
        }
        sendRealtime(gun);
        return enable ? "已注入故障" : "故障已恢复";
    }

    public synchronized String startByCard(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (!gun.plugged) {
            return "未插枪，无法启动";
        }
        if (gun.state == GunState.CHARGING) {
            return "已在充电";
        }
        if (isOcpp()) {
            if (!loggedIn) return "OCPP 未完成 BootNotification";
            if (!ocppCanStart(gun.gunNo)) throw new IllegalArgumentException("枪状态不允许启动");
            gun.parallelNo = null;
            gun.mainGun = 0;
            gun.startType = 2;
            ocppMarkAuthorizing(gun.gunNo);
            ocppClient.startTransaction(gun.gunNo, gun.physicalCard, 2);
            return "已向平台发起 OCPP Authorize / StartTransaction";
        }
        if (!loggedIn) return "未登录平台，请使用离线卡启动";
        if (startFailure(gun) != 0) throw new IllegalArgumentException("枪状态不允许启动");
        gun.parallelNo = null;
        gun.mainGun = 0;
        gun.startType = 2;
        gun.phase = "鉴权中";
        gun.authDeadline = System.currentTimeMillis() + 15000;
        sendStartRequest(gun);
        return "已向平台发起启动申请（0x31）";
    }

    public synchronized String terminate(String gunNo, int reason) {
        if (!top.yealike.ipe.protocol.codec.StopReasons.ALL.containsKey(reason))
            throw new IllegalArgumentException("停止原因必须在附录13.1范围内");
        Gun g = requireGun(gunNo);
        if (g.state != GunState.CHARGING) throw new IllegalArgumentException("当前未充电");
        advanceClock();
        advanceCharge(g);
        if (reason >= 0x59 && reason <= 0x5F || reason >= 0x84 && reason <= 0x88) {
            g.bmsErrorReason = reason;
            sendBms(g, FrameType.ERROR_REPORT);
        }
        stopCharge(g, reason);
        return "已按指定原因结束并生成账单";
    }

    public synchronized String stopLocal(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (gun.state != GunState.CHARGING) {
            return "当前未充电";
        }
        advanceClock();
        advanceCharge(gun);
        stopCharge(gun, 0x45);
        return "已本地停止并上送交易记录";
    }

    public synchronized String reportRealtime(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (isOcpp()) {
            if (gun.state == GunState.CHARGING) {
                sendRealtime(gun);
                return "已上送 OCPP MeterValues";
            }
            ocppClient.statusNotification(Integer.parseInt(gun.gunNo), gunRuntimeStatus(gun.gunNo), "NoError");
            return "已上送 OCPP StatusNotification";
        }
        sendRealtime(gun);
        return "已上送实时数据";
    }

    private Gun requireGun(String gunNo) {
        Gun gun = guns.get(gunNo);
        if (gun == null) {
            throw new IllegalArgumentException("不存在的枪号: " + gunNo);
        }
        return gun;
    }

    public synchronized Map<String, Object> state() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rateReady", rateReady);
        data.put("connected", connected);
        data.put("loggedIn", loggedIn);
        data.put("manualDisconnect", manualDisconnect);
        data.put("pileCode", properties.getPileCode());
        data.put("deviceId", selectedDeviceId);
        data.put("deviceSn", selectedDeviceSn);
        data.put("protocol", protocol.code());
        data.put("protocolLabel", protocol.label());
        data.put(
                "protocols",
                java.util.Arrays.stream(Protocol.values())
                        .map(p -> Map.of("code", p.code(), "label", p.label()))
                        .toList());
        data.put("platform", platformAddress());
        data.put("encryptionEnabled", properties.isEncryptionEnabled());
        List<Map<String, Object>> gunViews = new ArrayList<>();
        for (Gun gun : guns.values()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("gunNo", gun.gunNo);
            view.put("phase", gun.phase);
            view.put("capacityKwh", gun.capacityKwh);
            view.put("vin", gun.vin);
            view.put("targetEnergy", gun.targetEnergy);
            view.put("targetAmount", gun.targetAmount);
            view.put("targetMinutes", gun.targetMinutes);
            view.put("balance", gun.balance);
            view.put("physicalCard", gun.physicalCard);
            view.put("meter", gun.meterStart.add(gun.energy));
            view.put("parallelNo", gun.parallelNo);
            view.put("mainGun", gun.mainGun);
            view.put("lockStatus", gun.lockStatus);
            view.put("billing", gun.ledger == null ? List.of() : gun.ledger.view());
            view.put(
                    "billingModelNo",
                    gun.ledger == null ? null : gun.ledger.model().modelNo());
            view.put("state", gun.state.label());
            view.put("stateCode", gun.state.code());
            view.put("plugged", gun.plugged);
            view.put("fault", gun.fault);
            view.put("allowWork", gun.allowWork);
            view.put("transactionNo", gun.transactionNo);
            view.put("startTime", gun.startTime);
            view.put("energy", gun.energy);
            view.put("amount", gun.amount);
            view.put("soc", gun.soc);
            view.put("voltage", gun.voltage);
            view.put("current", gun.current);
            view.put("totalMinutes", gun.totalMinutes);
            view.put("remainMinutes", gun.remainMinutes);
            gunViews.add(view);
        }
        data.put("guns", gunViews);
        data.put("rateModel", rateModel);
        data.put("unitPrice", unitPrice());
        data.put("currentPeriod", ChargingLedger.period(rateModel, simulatedTime));
        data.put("simulatedTime", simulatedTime);
        data.put("speed", speed);
        data.put("maxPowerPercent", maxPowerPercent);
        data.put("offlineCards", offlineCards.values());
        data.put("history", List.copyOf(history));
        data.put("upgradeStatus", upgradeStatus);
        data.put("lastError", lastError);
        data.put("pendingTransactions", pendingTransactions.keySet());
        data.put("serverTime", LocalDateTime.now());
        return data;
    }

    public List<FrameLog> logs(int limit) {
        List<FrameLog> snapshot;
        synchronized (logs) {
            snapshot = new ArrayList<>(logs);
        }
        return snapshot.stream().limit(Math.max(limit, 1)).toList();
    }

    public void recordOcppLog(String direction, String type, String action, String payload, String summary) {
        FrameLog entry = new FrameLog(
                LocalDateTime.now(),
                direction,
                action == null || action.isBlank() ? type : action,
                type,
                0,
                payload,
                summary);
        synchronized (logs) {
            logs.addFirst(entry);
            while (logs.size() > 300) {
                logs.removeLast();
            }
        }
        log.debug("{} {} {}", direction, type, action);
    }

    private void recordLog(String direction, Frame frame, byte[] wire) {
        FrameType type = FrameType.ofOrNull(frame.frameType());
        FrameLog entry = new FrameLog(
                LocalDateTime.now(),
                direction,
                type == null ? "未知帧" : type.label(),
                String.format("0x%02X", frame.frameType()),
                frame.sequence(),
                frame.bodyHex(),
                summarize(frame));
        synchronized (logs) {
            logs.addFirst(entry);
            while (logs.size() > 300) {
                logs.removeLast();
            }
        }
        log.debug("{} {} seq={} {}", direction, entry.label(), frame.sequence(), frame.bodyHex());
    }

    private static String summarize(Frame frame) {
        FrameType type = FrameType.ofOrNull(frame.frameType());
        if (type == null) {
            return "未知帧 长度=" + frame.body().length;
        }
        try {
            return switch (type) {
                case LOGIN ->
                    "登录 桩=" + RegistrationFrames.decodeLogin(frame.body()).pileCode();
                case LOGIN_ACK ->
                    "登录应答 成功=" + RegistrationFrames.decodeLoginAck(frame.body()).success();
                case HEARTBEAT -> "心跳";
                case RATE_RESPONSE, RATE_SET ->
                    "计费模型 " + RegistrationFrames.decodeRateModel(frame.body()).modelNo();
                case READ_REALTIME ->
                    "读取实时 " + RealtimeFrames.decodeReadRealtime(frame.body()).gunNo();
                case REALTIME ->
                    "实时数据 " + RealtimeFrames.decodeRealtime(frame.body()).chargedEnergy() + "kWh";
                case REMOTE_START ->
                    "远程启动 " + OperationFrames.decodeRemoteStart(frame.body()).transactionNo();
                case REMOTE_STOP ->
                    "远程停机 " + OperationFrames.decodeRemoteStop(frame.body()).gunNo();
                case START_REQUEST ->
                    "主动启动 " + OperationFrames.decodeStartRequest(frame.body()).gunNo();
                case START_ACK ->
                    "启动确认 成功=" + OperationFrames.decodeStartAck(frame.body()).success();
                case TRANSACTION ->
                    "交易记录 " + OperationFrames.decodeTransaction(frame.body()).transactionNo();
                case TRANSACTION_ACK -> "交易确认";
                case TIME_SYNC -> "对时";
                case PARAM_SET -> "参数设置";
                case REBOOT -> "远程重启";
                case UPDATE -> "远程更新";
                case CHARGE_HANDSHAKE,
                        PARAM_CONFIG,
                        CHARGE_END,
                        ERROR_REPORT,
                        BMS_ABORT,
                        CHARGER_ABORT,
                        BMS_DEMAND,
                        BMS_INFO ->
                    type.label() + " " + BmsFrames.decode(type, frame.body()).values();
                default -> type.label() + " 长度=" + frame.body().length;
            };
        } catch (RuntimeException e) {
            return type.label() + " 解析失败";
        }
    }

    private int nextSequence() {
        return sequence.getAndUpdate(v -> v >= 0xFFFF ? 0 : v + 1) & 0xFFFF;
    }

    private static RegistrationFrames.RateModel defaultRateModel(String pileCode) {
        return new RegistrationFrames.RateModel(
                pileCode,
                "0000",
                new BigDecimal("1.20000"),
                new BigDecimal("0.40000"),
                new BigDecimal("1.00000"),
                new BigDecimal("0.35000"),
                new BigDecimal("0.80000"),
                new BigDecimal("0.30000"),
                new BigDecimal("0.50000"),
                new BigDecimal("0.20000"),
                0,
                java.util.Collections.nCopies(48, 2));
    }

    private void reportLock(Gun gun) {
        gun.lastLockAt = System.currentTimeMillis();
        if (isOcpp()) {
            return;
        }
        if (loggedIn)
            send(
                    FrameType.LOCK_REPORT,
                    MaintenanceFrames.encodeLockReport(new MaintenanceFrames.LockReport(
                            properties.getPileCode(),
                            Integer.parseInt(gun.gunNo),
                            gun.lockStatus,
                            gun.plugged ? 255 : 0,
                            95,
                            0,
                            "00000000")));
    }

    private void sendBms(Gun gun, FrameType type) {
        if (properties.getPileType() == 1 || !loggedIn) return;
        Map<String, Object> v = new LinkedHashMap<>();
        for (BmsFrames.Field f : BmsFrames.fields(type)) {
            Object value = switch (f.format()) {
                case "hex" -> "00".repeat(f.bytes());
                case "ascii" -> "";
                default -> Math.max(0, f.offset());
            };
            v.put(f.name(), value);
        }
        switch (type) {
            case CHARGE_HANDSHAKE -> {
                v.put("version", "010100");
                v.put("batteryType", 3);
                v.put(
                        "ratedCapacityAh",
                        BigDecimal.valueOf(gun.capacityKwh * 1000 / properties.getRatedVoltage())
                                .setScale(1, RoundingMode.HALF_UP));
                v.put("ratedVoltage", properties.getRatedVoltage());
                v.put("manufacturer", "SIMU");
                v.put("productionYear", 2026);
                v.put("productionMonth", 1);
                v.put("productionDay", 1);
                v.put("chargeCycles", 100);
                v.put("ownership", 1);
                v.put("vin", gun.vin);
            }
            case PARAM_CONFIG -> {
                v.put("maxCellVoltage", 4.2);
                v.put("maxCurrent", -properties.getRatedCurrent());
                v.put("capacityKwh", gun.capacityKwh);
                v.put("maxVoltage", properties.getRatedVoltage());
                v.put("maxTemperature", 60);
                v.put("soc", gun.soc);
                v.put("batteryVoltage", (int) properties.getRatedVoltage());
                v.put("chargerMaxVoltage", properties.getRatedVoltage());
                v.put("chargerMinVoltage", 200);
                v.put("chargerMaxCurrent", -properties.getRatedCurrent());
                v.put("chargerMinCurrent", 0);
            }
            case CHARGE_END -> {
                v.put("soc", gun.soc);
                v.put("minCellVoltage", 3.5);
                v.put("maxCellVoltage", 4.1);
                v.put("minTemperature", 25);
                v.put("maxTemperature", 35);
                v.put("minutes", gun.totalMinutes);
                v.put("energy", gun.energy.setScale(1, RoundingMode.HALF_UP));
                v.put("chargerNo", 1);
            }
            case BMS_DEMAND -> {
                v.put("demandVoltage", gun.voltage.setScale(1, RoundingMode.HALF_UP));
                v.put("demandCurrent", gun.current.negate().setScale(1, RoundingMode.HALF_UP));
                v.put("chargeMode", gun.soc >= 80 ? 1 : 2);
                v.put("measuredVoltage", gun.voltage.setScale(1, RoundingMode.HALF_UP));
                v.put("measuredCurrent", gun.current.negate().setScale(1, RoundingMode.HALF_UP));
                v.put("maxCellVoltageAndGroup", 410);
                v.put("soc", gun.soc);
                v.put("remainMinutes", gun.remainMinutes);
                v.put("outputVoltage", gun.voltage.setScale(1, RoundingMode.HALF_UP));
                v.put("outputCurrent", gun.current.negate().setScale(1, RoundingMode.HALF_UP));
                v.put("minutes", gun.totalMinutes);
            }
            case BMS_INFO -> {
                v.put("maxCellNo", 1);
                v.put("maxTemperature", 35);
                v.put("maxTemperatureProbe", 1);
                v.put("minTemperature", 25);
                v.put("minTemperatureProbe", 2);
                v.put("chargeStatus", 16);
            }
            case ERROR_REPORT -> {
                switch (gun.bmsErrorReason) {
                    case 0x5A -> v.put("chargerRecognitionTimeout", 1);
                    case 0x5B -> v.put("chargerReadyTimeout", 1);
                    case 0x5C -> v.put("chargerReadyTimeout", 4);
                    case 0x5E, 0x85 -> v.put("chargerChargingTimeout", 4);
                    case 0x87 -> v.put("chargerChargingTimeout", 16);
                    case 0x88 -> v.put("chargerStatisticsTimeout", 1);
                    case 0x59, 0x5F, 0x86 -> v.put("chargerStatisticsTimeout", 4);
                    default -> v.put("chargerChargingTimeout", 1);
                }
            }
            case BMS_ABORT -> v.put("reason", 1);
            case CHARGER_ABORT -> {
                v.put("reason", gun.fault ? 16 : 4);
                v.put("fault", gun.fault ? 256 : 0);
            }
            default -> {}
        }
        send(
                type,
                BmsFrames.encode(
                        type, new BmsFrames.Message(gun.transactionNo, properties.getPileCode(), gun.gunNo, v)));
    }

    public synchronized Map<String, Object> configureClock(
            LocalDateTime time, Double requestedSpeed, Integer advanceSeconds) {
        advanceClock();
        guns.values().forEach(this::advanceCharge);
        if (time != null) {
            if (guns.values().stream().anyMatch(g -> g.state == GunState.CHARGING))
                throw new IllegalArgumentException("充电中不能跳变时钟，请使用推进时间");
            if (time.getYear() < 2000 || time.getYear() > 2099) throw new IllegalArgumentException("时钟年份范围2000~2099");
            simulatedTime = time;
        }
        if (requestedSpeed != null) {
            if (!Double.isFinite(requestedSpeed) || requestedSpeed < 0 || requestedSpeed > 3600)
                throw new IllegalArgumentException("倍速范围0~3600");
            speed = requestedSpeed;
        }
        if (advanceSeconds != null) {
            if (advanceSeconds < 0 || advanceSeconds > 86400) throw new IllegalArgumentException("单次推进范围0~86400秒");
            simulatedTime = simulatedTime.plusSeconds(advanceSeconds);
            guns.values().forEach(this::advanceCharge);
        }
        clockNanos = System.nanoTime();
        return state();
    }

    public synchronized void configureVehicle(String gunNo, double capacity, int soc, String vin, String card) {
        Gun gun = requireGun(gunNo);
        if (gun.state == GunState.CHARGING || gun.phase.equals("鉴权中")) throw new IllegalArgumentException("请在启动前设置车辆");
        if (!Double.isFinite(capacity) || capacity < 1 || capacity > 1000 || soc < 0 || soc > 99)
            throw new IllegalArgumentException("容量1~1000kWh，SOC 0~99");
        if (vin == null || !vin.matches("[A-HJ-NPR-Z0-9]{17}")) throw new IllegalArgumentException("VIN须为17位合法字符");
        if (card == null || !card.matches("[0-9a-fA-F]{16}")) throw new IllegalArgumentException("物理卡号须为16位十六进制");
        gun.capacityKwh = capacity;
        gun.initialSoc = soc;
        gun.soc = soc;
        gun.socValue = soc;
        gun.vin = vin;
        gun.physicalCard = card.toUpperCase();
    }

    public synchronized void chargeLimits(String gunNo, double energy, double amount, int minutes) {
        Gun g = requireGun(gunNo);
        if (g.state == GunState.CHARGING) throw new IllegalArgumentException("请在启动前设置停止条件");
        if (!Double.isFinite(energy)
                || !Double.isFinite(amount)
                || energy < 0
                || energy > 1000
                || amount < 0
                || amount > 100000
                || minutes < 0
                || minutes > 600) throw new IllegalArgumentException("停止条件范围错误");
        g.targetEnergy = energy;
        g.targetAmount = amount;
        g.targetMinutes = minutes;
    }

    public synchronized String startOffline(String gunNo) {
        if (isOcpp()) throw new IllegalArgumentException("OCPP 1.6J 模拟器不支持离线卡启动");
        Gun gun = requireGun(gunNo);
        if (loggedIn) throw new IllegalArgumentException("离线卡仅在离线时使用");
        if (startFailure(gun) != 0) throw new IllegalArgumentException("枪未就绪");
        if (!offlineCards.containsKey(gun.physicalCard)) throw new IllegalArgumentException("卡不在离线白名单中");
        if (rateModel.modelNo().equals("0000")) throw new IllegalArgumentException("请先从平台同步费率");
        String tx = properties.getPileCode()
                + gun.gunNo
                + simulatedTime.format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%04d", nextSequence() % 10000);
        gun.parallelNo = null;
        gun.mainGun = 0;
        beginCharge(gun, tx, 4);
        return "离线卡启动成功，联网后补传账单";
    }

    public synchronized String startVin(String gunNo) {
        Gun gun = requireGun(gunNo);
        if (isOcpp()) {
            if (!loggedIn || !ocppCanStart(gun.gunNo)) throw new IllegalArgumentException("枪或连接未就绪");
            gun.parallelNo = null;
            gun.mainGun = 0;
            gun.startType = 5;
            ocppMarkAuthorizing(gun.gunNo);
            ocppClient.startTransaction(gun.gunNo, gun.vin, 5);
            return "已发起 OCPP VIN 鉴权（idTag=VIN）";
        }
        if (!loggedIn || startFailure(gun) != 0) throw new IllegalArgumentException("枪或连接未就绪");
        gun.parallelNo = null;
        gun.mainGun = 0;
        gun.startType = 5;
        gun.phase = "鉴权中";
        gun.authDeadline = System.currentTimeMillis() + 15000;
        send(
                FrameType.START_REQUEST,
                OperationFrames.encodeStartRequest(new OperationFrames.StartRequest(
                        properties.getPileCode(),
                        gunNo,
                        3,
                        false,
                        gun.physicalCard,
                        "0".repeat(32),
                        new StringBuilder(gun.vin).reverse().toString())));
        return "已发起VIN鉴权";
    }

    public synchronized String startParallel() {
        if (isOcpp()) throw new IllegalArgumentException("OCPP 1.6J 不支持并充启动");
        if (!loggedIn || guns.size() < 2 || pendingParallelNo != null)
            throw new IllegalArgumentException("并充需要在线、至少两枪且无待处理并充");
        for (Gun g : guns.values()) if (startFailure(g) != 0) throw new IllegalArgumentException("所有枪必须已插枪且就绪");
        pendingParallelNo = simulatedTime.format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        parallelDeadline = System.currentTimeMillis() + 15000;
        parallelAcks.clear();
        parallelCommands.clear();
        int i = 0;
        for (Gun g : guns.values()) {
            g.parallelNo = pendingParallelNo;
            g.mainGun = i++ == 0 ? 0 : 1;
            g.phase = "鉴权中";
            send(
                    FrameType.PARALLEL_START_REQUEST,
                    ParallelFrames.encodeParallelStartRequest(new ParallelFrames.ParallelStartRequest(
                            properties.getPileCode(),
                            g.gunNo,
                            1,
                            false,
                            g.physicalCard,
                            "0".repeat(32),
                            "",
                            g.mainGun,
                            pendingParallelNo)));
        }
        return "已提交全部枪并充鉴权";
    }

    private void handleParallelAck(Frame frame) {
        var ack = ParallelFrames.decodeParallelStartAck(frame.body());
        if (pendingParallelNo == null
                || !pendingParallelNo.equals(ack.parallelNo())
                || !ack.pileCode().equals(properties.getPileCode())) return;
        if (!ack.success()) {
            cancelParallel();
            return;
        }
        parallelAcks.put(ack.gunNo(), ack);
        if (parallelAcks.size() == guns.size()) {
            for (Gun g : guns.values())
                if (!g.plugged || g.fault || !g.allowWork) {
                    cancelParallel();
                    return;
                }
            for (Gun g : guns.values()) {
                var a = parallelAcks.get(g.gunNo);
                g.balance = a.balance();
                beginCharge(g, a.transactionNo(), 2);
            }
            pendingParallelNo = null;
            parallelAcks.clear();
        }
    }

    private void handleParallelRemote(Frame frame) {
        var c = ParallelFrames.decodeParallelRemoteStart(frame.body());
        Gun g = guns.get(c.gunNo());
        int fail = !c.pileCode().equals(properties.getPileCode()) ? 1 : startFailure(g);
        if (pendingParallelNo != null && !pendingParallelNo.equals(c.parallelNo())) fail = 2;
        boolean success = fail == 0;
        send(
                FrameType.PARALLEL_START_RESULT,
                ParallelFrames.encodeParallelStartResult(new ParallelFrames.ParallelStartResult(
                        c.transactionNo(),
                        properties.getPileCode(),
                        c.gunNo(),
                        success,
                        fail,
                        c.gunNo().equals("01") ? 0 : 1,
                        c.parallelNo())),
                frame.sequence());
        if (!success) {
            cancelParallel();
            return;
        }
        if (pendingParallelNo == null) {
            pendingParallelNo = c.parallelNo();
            parallelDeadline = System.currentTimeMillis() + 15000;
            parallelCommands.clear();
        }
        parallelCommands.put(c.gunNo(), c);
        g.phase = "鉴权中";
        if (parallelCommands.size() == guns.size()) {
            for (Gun gun : guns.values()) {
                var cmd = parallelCommands.get(gun.gunNo);
                gun.balance = cmd.balance();
                gun.physicalCard = cmd.physicalCard();
                gun.parallelNo = cmd.parallelNo();
                gun.mainGun = gun.gunNo.equals("01") ? 0 : 1;
                beginCharge(gun, cmd.transactionNo(), 1);
            }
            parallelCommands.clear();
            pendingParallelNo = null;
        }
    }

    private void cancelParallel() {
        parallelAcks.values().forEach(a -> {
            Gun g = guns.get(a.gunNo());
            if (g != null) {
                g.phase = "已插枪";
                beginCharge(g, a.transactionNo(), 2);
                stopCharge(g, 0x4A);
            }
        });
        parallelCommands.values().forEach(a -> {
            Gun g = guns.get(a.gunNo());
            if (g != null) {
                g.phase = "已插枪";
                beginCharge(g, a.transactionNo(), 1);
                stopCharge(g, 0x4A);
            }
        });
        guns.values().stream().filter(g -> g.phase.equals("鉴权中")).forEach(g -> g.phase = "已插枪");
        parallelAcks.clear();
        parallelCommands.clear();
        pendingParallelNo = null;
    }

    /** 协议由设备档案决定，保留方法仅用于兼容旧调用。 */
    public synchronized Map<String, Object> switchProtocol(String value) {
        throw new IllegalStateException("协议由设备档案决定，请重新选择设备");
    }

    private synchronized Map<String, Object> legacySwitchProtocol(String value) {
        Protocol next = Protocol.of(value);
        if (next == protocol) {
            return state();
        }
        guns.values().stream()
                .filter(g -> g.state == GunState.CHARGING)
                .toList()
                .forEach(g -> stopCharge(g, 0x83));
        disconnect();
        protocol = next;
        properties.setProtocol(next.code());
        connected = false;
        loggedIn = false;
        rateReady = false;
        lastError = null;
        guns.values().forEach(g -> {
            g.phase = g.plugged ? "已插枪" : "待机";
            g.lastHeartbeatAt = 0;
            g.lastRealtimeAt = 0;
            g.heartbeatAckAt = 0;
            g.waitingStart = null;
        });
        manualDisconnect = false;
        nextReconnectAt = 0;
        connect();
        return state();
    }

    private String platformAddress() {
        if (protocol == Protocol.OCPP16J) {
            String path =
                    properties.getOcppPath() == null || properties.getOcppPath().isBlank()
                            ? "/ocpp"
                            : properties.getOcppPath();
            if (!path.startsWith("/")) path = "/" + path;
            return "ws://"
                    + properties.getPlatformHost()
                    + ":"
                    + properties.getOcppPort()
                    + path
                    + "/"
                    + properties.getPileCode();
        }
        return properties.getPlatformHost() + ":" + properties.getPlatformPort();
    }

    public synchronized Map<String, Object> configuration() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("pileCode", properties.getPileCode());
        c.put("deviceId", selectedDeviceId);
        c.put("deviceSn", selectedDeviceSn);
        c.put("opsBaseUrl", properties.getOpsBaseUrl());
        c.put("gunCount", guns.size());
        c.put("pileType", properties.getPileType());
        c.put("protocol", protocol.code());
        c.put("protocolLabel", protocol.label());
        c.put("platformHost", properties.getPlatformHost());
        c.put("platformPort", properties.getPlatformPort());
        c.put("ocppPort", properties.getOcppPort());
        c.put("ocppPath", properties.getOcppPath());
        c.put("platformUrl", platformAddress());
        c.put("protocolVersion", properties.getProtocolVersion());
        c.put("programVersion", properties.getProgramVersion());
        c.put("ratedVoltage", properties.getRatedVoltage());
        c.put("ratedCurrent", properties.getRatedCurrent());
        c.put("maxPowerPercent", maxPowerPercent);
        c.put("heartbeatSeconds", properties.getHeartbeatSeconds());
        c.put("idleRealtimeSeconds", properties.getIdleRealtimeSeconds());
        c.put("chargingRealtimeSeconds", properties.getChargingRealtimeSeconds());
        c.put("encryptionEnabled", properties.isEncryptionEnabled());
        c.put("stateFile", properties.getStateFile());
        c.put("platformHttpPort", properties.getPlatformHttpPort());
        c.put("simCard", properties.getSimCard());
        c.put("networkType", properties.getNetworkType());
        c.put("operator", properties.getOperator());
        c.put("autoConnect", properties.isAutoConnect());
        c.put("reconnectSeconds", properties.getReconnectSeconds());
        c.put("transactionRetrySeconds", properties.getTransactionRetrySeconds());
        c.put("transactionMaxRetries", properties.getTransactionMaxRetries());
        c.put("rateModel", rateModel);
        c.put("upgradeStatus", upgradeStatus);
        return c;
    }

    private void persist() {
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("rate", java.util.HexFormat.of().formatHex(RegistrationFrames.encodeRateModel(rateModel)));
            p.setProperty("power", Integer.toString(maxPowerPercent));
            for (Gun g : guns.values()) {
                p.setProperty("meter." + g.gunNo, g.meter.toPlainString());
                p.setProperty("allow." + g.gunNo, Boolean.toString(g.allowWork));
                if (g.state == GunState.CHARGING && g.ledger != null) {
                    String key = "active." + g.gunNo + ".";
                    p.setProperty(key + "tx", g.transactionNo);
                    p.setProperty(key + "start", g.startTime.toString());
                    p.setProperty(key + "last", g.lastAdvance.toString());
                    p.setProperty(key + "meterStart", g.meterStart.toPlainString());
                    p.setProperty(key + "vin", g.vin);
                    p.setProperty(key + "card", g.physicalCard);
                    p.setProperty(key + "type", Integer.toString(g.startType));
                    p.setProperty(key + "soc", Integer.toString(g.soc));
                    g.ledger.checkpoint(p, key + "ledger.");
                }
            }
            for (var c : offlineCards.values()) p.setProperty("card." + c.physicalCard(), c.logicalCard());
            for (int i = 0; i < history.size(); i++)
                p.setProperty(
                        "history." + i,
                        java.util.HexFormat.of().formatHex(OperationFrames.encodeTransaction(history.get(i))));
            for (var e : pendingTransactions.entrySet())
                p.setProperty(
                        "tx." + e.getKey(),
                        java.util.HexFormat.of()
                                .formatHex(OperationFrames.encodeTransaction(e.getValue().transaction)));
            java.nio.file.Path path =
                    java.nio.file.Path.of(properties.getStateFile()).toAbsolutePath();
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (var out = java.nio.file.Files.newOutputStream(temp)) {
                p.store(out, "CloudQuickCharge simulator durable outbox");
            }
            java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            lastError = "保存模拟器状态失败: " + e.getMessage();
            log.warn(lastError);
        }
    }

    private void restore() {
        java.nio.file.Path path = java.nio.file.Path.of(properties.getStateFile());
        if (!java.nio.file.Files.exists(path)) return;
        try (var in = java.nio.file.Files.newInputStream(path)) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            if (p.containsKey("rate")) {
                var m = RegistrationFrames.decodeRateModel(
                        java.util.HexFormat.of().parseHex(p.getProperty("rate")));
                if (m.pileCode().equals(properties.getPileCode())) {
                    ChargingLedger.validate(m);
                    rateModel = m;
                }
            }
            maxPowerPercent = Integer.parseInt(p.getProperty("power", "100"));
            for (Gun g : guns.values()) {
                g.meter = new BigDecimal(p.getProperty("meter." + g.gunNo, "1000"));
                g.meterStart = g.meter;
                g.allowWork = Boolean.parseBoolean(p.getProperty("allow." + g.gunNo, "true"));
            }
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("card.")) {
                    String card = k.substring(5);
                    offlineCards.put(card, new OperationFrames.OfflineCard(p.getProperty(k), card));
                }
                if (k.startsWith("tx.")) {
                    var tx = OperationFrames.decodeTransaction(
                            java.util.HexFormat.of().parseHex(p.getProperty(k)));
                    if (tx.pileCode().equals(properties.getPileCode()))
                        pendingTransactions.put(
                                tx.transactionNo(),
                                new PendingTransaction(tx, properties.getTransactionMaxRetries(), 0));
                }
            }
            for (int i = 0; i < 100; i++) {
                String value = p.getProperty("history." + i);
                if (value == null) break;
                var tx = OperationFrames.decodeTransaction(
                        java.util.HexFormat.of().parseHex(value));
                if (tx.pileCode().equals(properties.getPileCode())) history.add(tx);
            }
            // An interrupted process cannot safely pretend the hardware kept charging. Settle the
            // last durable meter
            // checkpoint as power loss.
            for (Gun g : guns.values()) {
                String key = "active." + g.gunNo + ".";
                if (!p.containsKey(key + "tx")) continue;
                g.transactionNo = p.getProperty(key + "tx");
                if (!g.transactionNo.startsWith(properties.getPileCode())) continue;
                g.ledger = ChargingLedger.restore(p, key + "ledger.");
                g.startTime = LocalDateTime.parse(p.getProperty(key + "start"));
                g.lastAdvance = LocalDateTime.parse(p.getProperty(key + "last"));
                g.meterStart = new BigDecimal(p.getProperty(key + "meterStart"));
                g.vin = p.getProperty(key + "vin");
                g.physicalCard = p.getProperty(key + "card");
                g.startType = Integer.parseInt(p.getProperty(key + "type"));
                g.soc = Integer.parseInt(p.getProperty(key + "soc"));
                g.energy = g.ledger.energy();
                g.amount = g.ledger.amount();
                g.state = GunState.CHARGING;
                LocalDateTime current = simulatedTime;
                simulatedTime = g.lastAdvance;
                stopCharge(g, 0x83);
                simulatedTime = current;
            }
        } catch (Exception e) {
            lastError = "恢复模拟器状态失败: " + e.getMessage();
            log.warn(lastError);
        }
    }

    private static final class PendingTransaction {
        private final OperationFrames.Transaction transaction;
        private int retriesLeft;
        private long nextAt;

        private PendingTransaction(OperationFrames.Transaction transaction, int retriesLeft, long nextAt) {
            this.transaction = transaction;
            this.retriesLeft = retriesLeft;
            this.nextAt = nextAt;
        }
    }
}
