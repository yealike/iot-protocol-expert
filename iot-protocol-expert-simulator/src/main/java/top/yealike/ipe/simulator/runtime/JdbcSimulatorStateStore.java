package top.yealike.ipe.simulator.runtime;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.protocol.codec.frames.OperationFrames.Transaction;

@Repository
@DependsOnDatabaseInitialization
public class JdbcSimulatorStateStore implements SimulatorStateStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcSimulatorStateStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    @Override
    public Optional<Properties> load(DeviceKey key) {
        return jdbc
                .query(
                        "SELECT snapshot FROM ipe_simulator.device_state WHERE state_key = ?",
                        (rs, row) -> {
                            Properties snapshot = new Properties();
                            try {
                                snapshot.load(new StringReader(rs.getString(1)));
                            } catch (IOException e) {
                                throw new IllegalStateException("模拟器数据库快照格式错误", e);
                            }
                            return snapshot;
                        },
                        key.value())
                .stream()
                .findFirst();
    }

    @Override
    public void save(DeviceKey key, Properties snapshot, List<Transaction> history) {
        StringWriter writer = new StringWriter();
        try {
            snapshot.store(writer, "Simulator database checkpoint");
        } catch (IOException e) {
            throw new IllegalStateException("模拟器快照序列化失败", e);
        }
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ipe_simulator.device_state (state_key, device_id, protocol, pile_code, snapshot)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (state_key) DO UPDATE SET snapshot = EXCLUDED.snapshot, updated_at = current_timestamp
                    """, key.value(), key.deviceId(), key.protocol(), key.pileCode(), writer.toString());
            jdbc.batchUpdate("""
                    INSERT INTO ipe_simulator.charging_record
                    (state_key, transaction_no, device_id, protocol, pile_code, gun_no, start_time, end_time,
                     meter_start, meter_end, total_energy, loss_total_energy, total_amount, stop_reason, bill)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (state_key, transaction_no) DO NOTHING
                    """, history, 100, (ps, bill) -> {
                ps.setString(1, key.value());
                ps.setString(2, bill.transactionNo());
                ps.setObject(3, key.deviceId());
                ps.setString(4, key.protocol());
                ps.setString(5, bill.pileCode());
                ps.setString(6, bill.gunNo());
                ps.setObject(7, bill.startTime());
                ps.setObject(8, bill.endTime());
                ps.setBigDecimal(9, bill.meterStart());
                ps.setBigDecimal(10, bill.meterEnd());
                ps.setBigDecimal(11, bill.totalEnergy());
                ps.setBigDecimal(12, bill.lossTotalEnergy());
                ps.setBigDecimal(13, bill.totalAmount());
                ps.setInt(14, bill.stopReason());
                ps.setString(15, json.writeValueAsString(bill));
            });
        });
    }
}
