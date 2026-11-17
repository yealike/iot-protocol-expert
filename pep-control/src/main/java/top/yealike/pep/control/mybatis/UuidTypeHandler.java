package top.yealike.pep.control.mybatis;

import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** PostgreSQL UUID parameters and results, including nullable references. */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, value, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int column) throws SQLException {
        return cs.getObject(column, UUID.class);
    }
}
