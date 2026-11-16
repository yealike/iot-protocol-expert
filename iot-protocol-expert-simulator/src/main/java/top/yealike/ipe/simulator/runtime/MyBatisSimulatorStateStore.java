package top.yealike.ipe.simulator.runtime;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.codec.frames.OperationFrames.Transaction;
import top.yealike.ipe.simulator.mapper.SimulatorStateMapper;

@Repository
@DependsOnDatabaseInitialization
public class MyBatisSimulatorStateStore implements SimulatorStateStore {
    private final SimulatorStateMapper states;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public MyBatisSimulatorStateStore(
            SimulatorStateMapper states, PlatformTransactionManager manager, ObjectMapper json) {
        this.states = states;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    @Override
    public Optional<Properties> load(DeviceKey key) {
        String stored = states.selectSnapshot(key.value());
        if (stored == null) {
            return Optional.empty();
        }
        Properties snapshot = new Properties();
        try {
            snapshot.load(new StringReader(stored));
        } catch (IOException e) {
            throw new IllegalStateException("模拟器数据库快照格式错误", e);
        }
        return Optional.of(snapshot);
    }

    @Override
    public void save(DeviceKey key, Properties snapshot, List<Transaction> history) {
        StringWriter writer = new StringWriter();
        try {
            snapshot.store(writer, "Simulator database checkpoint");
        } catch (IOException e) {
            throw new IllegalStateException("模拟器快照序列化失败", e);
        }
        List<SimulatorStateMapper.ChargeRecordRow> rows =
                history.stream().map(bill -> row(key, bill)).toList();
        transactions.executeWithoutResult(status -> {
            states.upsertState(key.value(), key.deviceId(), key.protocol(), key.pileCode(), writer.toString());
            if (!rows.isEmpty()) {
                states.insertChargingRecords(rows);
            }
        });
    }

    private SimulatorStateMapper.ChargeRecordRow row(DeviceKey key, Transaction bill) {
        SimulatorStateMapper.ChargeRecordRow row = new SimulatorStateMapper.ChargeRecordRow();
        row.stateKey = key.value();
        row.transactionNo = bill.transactionNo();
        row.deviceId = key.deviceId();
        row.protocol = key.protocol();
        row.pileCode = bill.pileCode();
        row.gunNo = bill.gunNo();
        row.startTime = bill.startTime();
        row.endTime = bill.endTime();
        row.meterStart = bill.meterStart();
        row.meterEnd = bill.meterEnd();
        row.totalEnergy = bill.totalEnergy();
        row.lossTotalEnergy = bill.lossTotalEnergy();
        row.totalAmount = bill.totalAmount();
        row.stopReason = bill.stopReason();
        row.billJson = json.writeValueAsString(bill);
        return row;
    }
}
