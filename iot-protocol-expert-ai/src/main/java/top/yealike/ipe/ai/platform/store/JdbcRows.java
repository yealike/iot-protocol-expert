package top.yealike.ipe.ai.platform.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import top.yealike.ipe.ai.support.JsonSupport;

/** JDBC 行读取工具：UUID/timestamptz/jsonb 的显式转换。 */
final class JdbcRows {

    private JdbcRows() {}

    static UUID uuid(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, UUID.class);
    }

    static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp timestamp = rows.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static Map<String, Object> json(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        return value == null || value.isBlank() ? Map.of() : JsonSupport.readMap(value);
    }
}
