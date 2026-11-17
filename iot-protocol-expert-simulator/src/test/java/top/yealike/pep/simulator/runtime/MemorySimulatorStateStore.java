package top.yealike.pep.simulator.runtime;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import top.yealike.pep.codec.frames.OperationFrames.Transaction;

final class MemorySimulatorStateStore implements SimulatorStateStore {
    private final ConcurrentHashMap<String, Properties> snapshots = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Transaction> bills = new ConcurrentHashMap<>();

    @Override
    public Optional<Properties> load(DeviceKey key) {
        return Optional.ofNullable(snapshots.get(key.value())).map(p -> (Properties) p.clone());
    }

    @Override
    public void save(DeviceKey key, Properties snapshot, List<Transaction> history) {
        snapshots.put(key.value(), (Properties) snapshot.clone());
        history.forEach(tx -> bills.putIfAbsent(key.value() + "/" + tx.transactionNo(), tx));
    }
}
