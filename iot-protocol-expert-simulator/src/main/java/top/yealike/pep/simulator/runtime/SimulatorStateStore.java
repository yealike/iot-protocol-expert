package top.yealike.pep.simulator.runtime;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import top.yealike.pep.codec.frames.OperationFrames.Transaction;
import top.yealike.pep.simulator.domain.DeviceKey;

/** 状态快照与完成账单必须在同一事务中保存。 */
public interface SimulatorStateStore {
    Optional<Properties> load(DeviceKey key);

    void save(DeviceKey key, Properties snapshot, List<Transaction> history);
}
