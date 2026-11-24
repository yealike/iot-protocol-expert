package top.yealike.pep.control.audit;

import org.apache.ibatis.annotations.Param;

public interface AiAuditLogMapper {

    /** 写入 Agent 或委派任务的审计记录。 */
    int auditDelegation(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("targetType") String targetType,
            @Param("target") String target,
            @Param("result") String result,
            @Param("reason") String reason);

    int audit(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("target") String target,
            @Param("result") String result);
}
