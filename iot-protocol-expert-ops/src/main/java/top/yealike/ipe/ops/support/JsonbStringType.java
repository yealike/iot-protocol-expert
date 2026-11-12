package top.yealike.ipe.ops.support;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * jsonb 列与 Java String 的映射。
 *
 * <p>为什么不用 {@code @JdbcTypeCode(SqlTypes.JSON)}：Spring Boot 4 只带 Jackson 3 （{@code
 * tools.jackson.*}），而 Hibernate 7.2 的 JSON format mapper 仍然只认 Jackson 2 / JSON-B。与其为一个 JSON 列再引一套
 * Jackson，不如把 JSON 当作不透明字符串： 应用层用同一个 Jackson 3 实例做严格解析与校验，数据库只负责存储与索引。
 *
 * <p>{@code setObject(index, value, Types.OTHER)} 让驱动以 unknown 类型发送参数， PostgreSQL
 * 会按目标列类型（jsonb）完成转换，无需额外语法。
 */
public class JsonbStringType implements UserType<String> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<String> returnedClass() {
        return String.class;
    }

    @Override
    public boolean equals(String x, String y) {
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode(String x) {
        return Objects.hashCode(x);
    }

    @Override
    public String nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        return rs.getString(position);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, String value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value, Types.OTHER);
        }
    }

    @Override
    public String deepCopy(String value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(String value) {
        return value;
    }

    @Override
    public String assemble(Serializable cached, Object owner) {
        return cached == null ? null : cached.toString();
    }
}
