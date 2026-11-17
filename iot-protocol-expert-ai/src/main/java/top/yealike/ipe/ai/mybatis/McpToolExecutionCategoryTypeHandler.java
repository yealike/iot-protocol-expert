package top.yealike.ipe.ai.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import top.yealike.ipe.ai.contract.McpToolExecutionCategory;

/** 保留旧 Store 的容错语义：未知或空类别按 UNKNOWN 处理，而不是让整次查询失败。 */
public class McpToolExecutionCategoryTypeHandler extends BaseTypeHandler<McpToolExecutionCategory> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, McpToolExecutionCategory parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public McpToolExecutionCategory getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return McpToolExecutionCategory.parse(rs.getString(columnName));
    }

    @Override
    public McpToolExecutionCategory getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return McpToolExecutionCategory.parse(rs.getString(columnIndex));
    }

    @Override
    public McpToolExecutionCategory getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return McpToolExecutionCategory.parse(cs.getString(columnIndex));
    }
}
