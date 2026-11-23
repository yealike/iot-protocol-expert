package top.yealike.pep.ops.audit.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.ops.audit.domain.AuditLog;

public interface AuditLogMapper {

    List<AuditLog> selectRecent(@Param("limit") int limit);

    int insert(AuditLog log);

    default List<AuditLog> findRecent200() {
        return selectRecent(200);
    }

    default AuditLog save(AuditLog log) {
        insert(log);
        return log;
    }
}
