package top.yealike.ipe.simulator.runtime;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import top.yealike.ipe.protocol.codec.frames.OperationFrames.Transaction;

/** 状态快照与完成账单必须在同一事务中保存。 */
public interface SimulatorStateStore {
    record DeviceKey(Long deviceId, String protocol, String pileCode) {
        public String value() {
            return (deviceId == null ? "configured" : "device/" + deviceId) + "/" + protocol + "/" + pileCode;
        }
    }

    Optional<Properties> load(DeviceKey key);

    void save(DeviceKey key, Properties snapshot, List<Transaction> history);
}
