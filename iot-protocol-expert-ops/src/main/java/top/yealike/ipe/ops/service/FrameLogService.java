package top.yealike.ipe.ops.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.repository.DeviceInstanceRepository;
import top.yealike.ipe.ops.domain.FrameLog;
import top.yealike.ipe.ops.dto.OpsDtos;
import top.yealike.ipe.ops.repository.FrameLogRepository;

@Service
public class FrameLogService {

    private final FrameLogRepository frames;
    private final DeviceInstanceRepository devices;

    public FrameLogService(FrameLogRepository frames, DeviceInstanceRepository devices) {
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
        Specification<FrameLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (deviceId != null) predicates.add(cb.equal(root.get("deviceId"), deviceId));
            if (protocol != null && !protocol.isBlank()) predicates.add(cb.equal(root.get("protocol"), protocol));
            if (protocolDeviceId != null && !protocolDeviceId.isBlank()) {
                predicates.add(cb.equal(root.get("protocolDeviceId"), protocolDeviceId));
            }
            if (messageId != null && !messageId.isBlank()) predicates.add(cb.equal(root.get("messageId"), messageId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), to));
            query.orderBy(cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return frames.findAll(spec).stream()
                .limit(Math.min(Math.max(limit, 1), 1000))
                .toList();
    }

    public List<FrameLog> recent(Long deviceId) {
        return deviceId == null
                ? frames.findAll().stream().limit(200).toList()
                : frames.findTop200ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    private java.util.Optional<DeviceInstance> deviceOf(String protocol, String code) {
        if (code == null || code.isBlank()) return java.util.Optional.empty();
        return devices.findByProtocolAndProtocolDeviceId(protocol, code);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
