package top.yealike.pep.control.audit;

import org.apache.ibatis.annotations.Param;

public interface AiAuditLogMapper {

    int audit(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("target") String target,
            @Param("result") String result);
}
