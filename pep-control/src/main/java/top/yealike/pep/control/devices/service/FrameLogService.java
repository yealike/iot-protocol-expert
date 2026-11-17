package top.yealike.pep.control.devices.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.devices.entity.DeviceInstance;
import top.yealike.pep.control.devices.domain.FrameLog;
import top.yealike.pep.control.api.dto.OpsDtos;
import top.yealike.pep.control.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.control.devices.mapper.FrameLogMapper;

@Service
public class FrameLogService {

    private final FrameLogMapper frames;
    private final DeviceInstanceMapper devices;

    public FrameLogService(FrameLogMapper frames, DeviceInstanceMapper devices) {
        this.frames = frames;
        this.devices = devices;
    }

    /** 协议通道转交的原始报文与解析摘要。平台事务内留存，未知设备也保留协议侧编号。 */
    @Transactional
    public FrameLog record(OpsDtos.FrameRequest request) {
        FrameLog frame = new FrameLog();
        frame.protocol = DeviceService.normalizeProtocol(request.protocol());
        frame.protocolDeviceId = request.protocolDeviceId();
        deviceOf(frame.protocol, frame.protocolDeviceId).ifPresent(device -> frame.deviceId = device.id);
        frame.direction = request.direction();
        frame.frameType = request.frameType();
        frame.frameLabel = truncate(request.frameLabel(), 64);
        frame.messageId = truncate(request.messageId(), 64);
        frame.sequenceNo = request.sequenceNo();
        frame.encrypted = request.encrypted();
        frame.bodyHex = truncate(request.bodyHex(), 8192);
        frame.wireHex = truncate(request.wireHex(), 8192);
        frame.summary = truncate(request.summary(), 512);
        frame.errorMessage = truncate(request.errorMessage(), 512);
        frame.traceId = truncate(request.traceId(), 64);
        frame.createdAt = request.createdAt() == null ? LocalDateTime.now() : request.createdAt();
        return frames.save(frame);
    }

    public List<FrameLog> search(
            Long deviceId,
            String protocol,
            String protocolDeviceId,
            String messageId,
            LocalDateTime from,
            LocalDateTime to,
            int limit) {
        return frames.search(
                deviceId, protocol, protocolDeviceId, messageId, from, to, Math.min(Math.max(limit, 1), 1000));
    }

    public List<FrameLog> recent(Long deviceId) {
        return deviceId == null ? frames.selectRecent(200) : frames.findTop200ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    private java.util.Optional<DeviceInstance> deviceOf(String protocol, String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        return devices.findByProtocolAndProtocolDeviceId(protocol, code);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
