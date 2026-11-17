package top.yealike.pep.control.mybatis;

import java.sql.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import top.yealike.pep.control.support.JsonSupport;

/** Explicit JSON result handlers preserve each record component's collection type. */
public abstract class JsonTypeHandler<T> extends BaseTypeHandler<T> {
    protected abstract T parse(String value);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T value, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, JsonSupport.write(value), Types.OTHER);
    }

    @Override
    public T getNullableResult(ResultSet rs, String column) throws SQLException {
        return parse(rs.getString(column));
    }

    @Override
    public T getNullableResult(ResultSet rs, int column) throws SQLException {
        return parse(rs.getString(column));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int column) throws SQLException {
        return parse(cs.getString(column));
    }
}
