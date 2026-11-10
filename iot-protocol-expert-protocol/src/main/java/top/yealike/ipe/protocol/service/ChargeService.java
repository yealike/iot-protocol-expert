package top.yealike.ipe.protocol.service;

import top.yealike.ipe.protocol.codec.Frame;
import top.yealike.ipe.protocol.codec.FrameType;
import top.yealike.ipe.protocol.codec.frames.OperationFrames;
import top.yealike.ipe.protocol.config.ProtocolProperties;
import top.yealike.ipe.protocol.domain.ChargeOrderEntity;
import top.yealike.ipe.protocol.domain.DeviceEntity;
import top.yealike.ipe.protocol.netty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;

/** 启动/停止充电与订单联动。 */
@Service
public class ChargeService {

    private static final Logger log = LoggerFactory.getLogger(ChargeService.class);

    private final DeviceService deviceService;
    private final OrderService orderService;
    private final GunStateService gunStateService;
    private final SessionRegistry sessions;
    private final ProtocolProperties properties;

    public ChargeService(DeviceService deviceService, OrderService orderService,
                         GunStateService gunStateService, SessionRegistry sessions,
                         ProtocolProperties properties) {
        this.deviceService = deviceService;
        this.orderService = orderService;
        this.gunStateService = gunStateService;
        this.sessions = sessions;
        this.properties = properties;
    }

    public record CommandResult(boolean success, String transactionNo, String message) {
    }

    /** 桩主动申请启动（0x31）的鉴权结果。 */
    public OperationFrames.StartAck handleStartRequest(OperationFrames.StartRequest request) {
        String pileCode = request.pileCode();
        int gun=Integer.parseInt(request.gunNo());
        if(gun<1||gun>deviceService.find(pileCode).getGunCount())throw new IllegalArgumentException("枪号不属于设备");
        DeviceEntity device = deviceService.find(pileCode);
        if (!device.isAllowWork()) {
            return new OperationFrames.StartAck("0".repeat(32), pileCode, request.gunNo(),
                    "00000000", BigDecimal.ZERO, false, 0x05);
        }
        if (orderService.active(pileCode, request.gunNo()) != null) {
            return new OperationFrames.StartAck("0".repeat(32), pileCode, request.gunNo(),
                    "00000000", new BigDecimal("1000.00"), false, 0x0A);
        }
        ChargeOrderEntity order = orderService.create(pileCode, request.gunNo(), request.startType(),
                request.account(), "00000000");
        log.info("桩 {} 枪 {} 主动启动通过，订单 {}", pileCode, request.gunNo(), order.getTransactionNo());
        return new OperationFrames.StartAck(order.getTransactionNo(), pileCode, request.gunNo(),
                "00000000", new BigDecimal("1000.00"), true, 0x00);
    }

    public CommandResult remoteStart(String pileCode, String gunNo, String logicalCard,
                                     String physicalCard, BigDecimal balance) {
        DeviceEntity device = deviceService.find(pileCode);
        if(gunNo==null||!gunNo.matches("[0-9]{2}")||Integer.parseInt(gunNo)<1||Integer.parseInt(gunNo)>device.getGunCount())throw new IllegalArgumentException("枪号不属于设备");
        if(balance!=null&&balance.signum()<0)throw new IllegalArgumentException("余额不能为负");
        if (!device.isAllowWork()) {
            throw new IllegalStateException("桩已停用，拒绝启动: " + pileCode);
        }
        if (!sessions.isOnline(pileCode)) {
            throw new DeviceService.DeviceOfflineException("桩不在线: " + pileCode);
        }
        ChargeOrderEntity order = orderService.create(pileCode, gunNo, 0x01,
                physicalCard, logicalCard);
        OperationFrames.RemoteStart command = new OperationFrames.RemoteStart(
                order.getTransactionNo(), pileCode, gunNo, logicalCard, physicalCard,
                balance == null ? new BigDecimal("1000.00") : balance);
        try {
            Frame ack = sessions.request(pileCode, FrameType.REMOTE_START, FrameType.REMOTE_START_ACK,
                    OperationFrames.encodeRemoteStart(command), timeout());
            OperationFrames.RemoteStartAck result = OperationFrames.decodeRemoteStartAck(ack.body());
            if (result.success()) {
                orderService.markCharging(order.getTransactionNo());
                return new CommandResult(true, order.getTransactionNo(), "远程启动成功");
            }
            if(result.failReason()!=5) orderService.fail(order.getTransactionNo(), startFailureReason(result.failReason()));
            return new CommandResult(false, order.getTransactionNo(),
                    (result.failReason()==5?"未插枪，60秒内插枪后自动补启动":"桩拒绝启动: " + startFailureReason(result.failReason())));
        } catch (RuntimeException e) {
            orderService.fail(order.getTransactionNo(), e.getMessage());
            throw e;
        }
    }

    public CommandResult remoteStop(String pileCode, String gunNo) {
        ChargeOrderEntity order = orderService.active(pileCode, gunNo);
        if (order == null) {
            throw new IllegalStateException("枪 " + gunNo + " 没有进行中的订单");
        }
        if (!sessions.isOnline(pileCode)) {
            throw new DeviceService.DeviceOfflineException("桩不在线: " + pileCode);
        }
        Frame ack = sessions.request(pileCode, FrameType.REMOTE_STOP, FrameType.STOP_ACK,
                OperationFrames.encodeRemoteStop(new OperationFrames.RemoteStop(pileCode, gunNo)), timeout());
        OperationFrames.StopAck result = OperationFrames.decodeStopAck(ack.body());
        if (result.success()) {
            orderService.stop(order.getTransactionNo(), 0x40);
            return new CommandResult(true, order.getTransactionNo(), "远程停机成功，等待交易记录");
        }
        return new CommandResult(false, order.getTransactionNo(),
                "桩停机失败，原因=" + result.failReason());
    }

    public java.util.List<CommandResult> remoteParallelStart(String pileCode, BigDecimal balance) {
        DeviceEntity device=deviceService.find(pileCode);
        if(!device.isAllowWork()||device.getGunCount()<2)throw new IllegalArgumentException("并充需要启用的多枪设备");
        if(!sessions.isOnline(pileCode))throw new DeviceService.DeviceOfflineException("桩不在线");
        java.util.List<ChargeOrderEntity> orders=new java.util.ArrayList<>();
        for(int i=1;i<=device.getGunCount();i++)if(orderService.active(pileCode,String.format("%02d",i))!=null)throw new IllegalStateException("所有枪必须空闲");
        String parallelNo=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        java.util.List<CommandResult> results=new java.util.ArrayList<>();
        try {
            for(int i=1;i<=device.getGunCount();i++)orders.add(orderService.create(pileCode,String.format("%02d",i),1,"00000000D14B0A54","00000000"));
            for(var order:orders) {
                var c=new top.yealike.ipe.protocol.codec.frames.ParallelFrames.ParallelRemoteStart(order.getTransactionNo(),pileCode,order.getGunNo(),"00000000","00000000D14B0A54",balance==null?new BigDecimal("1000"):balance,parallelNo);
                Frame frame=sessions.request(pileCode,FrameType.PARALLEL_REMOTE_START,FrameType.PARALLEL_START_RESULT,top.yealike.ipe.protocol.codec.frames.ParallelFrames.encodeParallelRemoteStart(c),timeout());
                var ack=top.yealike.ipe.protocol.codec.frames.ParallelFrames.decodeParallelStartResult(frame.body());
                if(!ack.success())throw new IllegalStateException("枪"+order.getGunNo()+"拒绝并充: "+ack.failReason());
                results.add(new CommandResult(true,order.getTransactionNo(),"并充指令已接受，等待全部枪就绪"));
            }
            return results;
        } catch(RuntimeException e) {orders.forEach(o->orderService.fail(o.getTransactionNo(),e.getMessage()));throw e;}
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(properties.getPendingTimeoutSeconds(), 3));
    }

    private static String startFailureReason(int code) {
        return switch (code) {
            case 0x01 -> "设备编号不匹配";
            case 0x02 -> "枪已在充电";
            case 0x03 -> "设备故障";
            case 0x04 -> "设备离线";
            case 0x05 -> "未插枪";
            default -> "未知原因(" + code + ")";
        };
    }
}