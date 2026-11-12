package top.yealike.ipe.ops.telemetry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TdengineTelemetryStore {
    private static final String DB = "ipe_telemetry";
    private final String url;
    private final String user;
    private final String password;

    public TdengineTelemetryStore(@Value("${ipe.ops.tdengine.url}") String url,
                                  @Value("${ipe.ops.tdengine.username}") String user,
                                  @Value("${ipe.ops.tdengine.password}") String password) {
        this.url = url; this.user = user; this.password = password;
    }

    record Sample(long id, String orderNo, Timestamp time, String source, BigDecimal voltage,
                  BigDecimal current, BigDecimal power, BigDecimal gunTemp, BigDecimal batteryTemp, BigDecimal soc) {}

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + DB + " PRECISION 'us' KEEP 3650");
            statement.execute("CREATE STABLE IF NOT EXISTS " + DB + ".samples "
                    + "(ts TIMESTAMP, time_source VARCHAR(16), voltage DOUBLE, current_amp DOUBLE, power_kw DOUBLE, "
                    + "gun_temp DOUBLE, battery_temp DOUBLE, soc DOUBLE) TAGS (marker INT)");
            return connection;
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
    }

    static String table(String orderNo) {
        try {
            return "o_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(orderNo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    void write(Sample sample) throws SQLException {
        if (!"DEVICE".equals(sample.source()) && !"RECEIVED".equals(sample.source()))
            throw new IllegalArgumentException("Invalid telemetry time source");
        String table = table(sample.orderNo());
        List<String> columns = new ArrayList<>(List.of("ts", "time_source"));
        var instant = sample.time().toInstant();
        long micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
        List<String> values = new ArrayList<>(List.of(Long.toString(micros), "'" + sample.source() + "'"));
        add(columns, values, "voltage", sample.voltage());
        add(columns, values, "current_amp", sample.current());
        add(columns, values, "power_kw", sample.power());
        add(columns, values, "gun_temp", sample.gunTemp());
        add(columns, values, "battery_temp", sample.batteryTemp());
        add(columns, values, "soc", sample.soc());
        try (var conn = connect(); var statement = conn.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + DB + "." + table + " USING " + DB + ".samples TAGS (0)");
            statement.execute("INSERT INTO " + DB + "." + table + " (" + String.join(",", columns)
                    + ") VALUES (" + String.join(",", values) + ")");
        }
    }

    private static void add(List<String> columns, List<String> values, String column, BigDecimal value) {
        if (value != null) { columns.add(column); values.add(value.toPlainString()); }
    }

    List<OrderTelemetryService.Point> query(String orderNo) {
        String sql = "SELECT ts,time_source,voltage,current_amp,power_kw,gun_temp,battery_temp,soc FROM "
                + DB + ".samples WHERE tbname = '" + table(orderNo) + "' ORDER BY ts LIMIT 10001";
        try (var conn = connect(); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            List<OrderTelemetryService.Point> rows = new ArrayList<>();
            while (rs.next()) rows.add(new OrderTelemetryService.Point(rs.getTimestamp(1).toInstant(), rs.getString(2),
                    rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                    rs.getBigDecimal(7), rs.getBigDecimal(8)));
            return rows;
        } catch (SQLException ex) { throw new IllegalStateException("TDengine 时序查询失败", ex); }
    }
}
