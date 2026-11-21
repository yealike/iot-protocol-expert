package top.yealike.pep.ops.devices.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.ops.api.dto.OpsDtos;
import top.yealike.pep.ops.devices.domain.FrameLog;
import top.yealike.pep.ops.devices.entity.DeviceInstance;
import top.yealike.pep.ops.devices.mapper.DeviceInstanceMapper;
import top.yealike.pep.ops.devices.mapper.DeviceProtocolMappingMapper;
import top.yealike.pep.ops.devices.mapper.FrameLogMapper;

@Service
public class FrameLogService {

    private final FrameLogMapper frames;
    private final DeviceInstanceMapper devices;
    private final DeviceProtocolMappingMapper mappings;

    public FrameLogService(FrameLogMapper frames, DeviceInstanceMapper devices, DeviceProtocolMappingMapper mappings) {
        this.frames = frames;
        this.devices = devices;
        this.mappings = mappings;
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

    /** AI 只读工具使用的严格设备范围查询；默认最多 50 条，不向模型暴露整段报文。 */
    public List<FrameLog> searchForAi(
            Long deviceId,
            String direction,
            Integer frameType,
            String frameLabel,
            LocalDateTime from,
            LocalDateTime to,
            int limit) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        return frames.searchForAi(
                deviceId, direction, frameType, frameLabel, from, to, Math.min(Math.max(limit, 1), 50));
    }

    /** 只允许读取归属于指定设备的帧，避免模型通过 frameId 探测其他设备报文。 */
    public Optional<FrameLog> findForDevice(Long deviceId, Long frameId) {
        if (deviceId == null || frameId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(frames.selectByIdAndDeviceId(frameId, deviceId));
    }

    private java.util.Optional<DeviceInstance> deviceOf(String protocol, String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        return mappings.findByProtocolAndThirdPartyDeviceId(protocol, code)
                .filter(mapping -> mapping.enabled)
                .flatMap(mapping -> devices.findById(mapping.deviceId));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
