package top.yealike.ipe.ai.mapper;

import org.apache.ibatis.annotations.Param;

public interface AuditLogMapper {

    int audit(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("target") String target,
            @Param("result") String result);
}
